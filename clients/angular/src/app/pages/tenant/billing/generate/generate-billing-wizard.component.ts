import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Subscription, interval, switchMap, takeWhile, tap } from 'rxjs';
import {
  BillingCommitResponse,
  BillingPreviewResponse,
  BillingPreviewSampleRow,
  ContributionsService,
  EnqueueBillingPayload,
  ScheduledJobRun,
} from '../../../../core/services/contributions.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { CurrencyFormatPipe } from '../../../../shared/pipes/currency-format.pipe';
import { TenantService } from '../../../../core/services/tenant.service';
import { PermissionService } from '../../../../core/security/permission.service';

/**
 * Friendly labels for the line tab strip. Matches the codes carried in
 * tenant.insuranceLines (string[]) — VEHICLE/MOTOR are aliases per
 * shared.insurance.InsuranceLine.from(); the wizard surfaces them as
 * "Motor" to match what the operator sees on the schemes form.
 */
const LINE_LABELS: Record<string, string> = {
  HEALTH:     'Health',
  VEHICLE:    'Motor',
  MOTOR:      'Motor',
  PROPERTY:   'Property',
  LIFE:       'Life',
  FUNERAL:    'Funeral',
  TRAVEL:     'Travel',
  DISABILITY: 'Disability',
  GROUP:      'Group',
};

type WizardStep = 'filters' | 'preview' | 'committed';

/**
 * One invoice the run would generate. Built by grouping the preview's
 * per-person sample rows on (groupId | memberId, currency) — the same
 * routing key the backend uses in {@code BillingService.computeRoutingKey}.
 */
interface SampleInvoice {
  type: 'GROUP' | 'INDIVIDUAL';
  /** Group name on a group invoice; the lead member's display name on an individual. */
  holderName: string;
  /** Stable id for *ngFor — uses the routing key. */
  routingKey: string;
  currencyCode: string;
  /** Member's-line + dependant-line rows for this invoice, ordered with the member first. */
  lines: BillingPreviewSampleRow[];
  total: number;
}

/** Short-poll cadence — see CLAUDE memory: "Stats must be server-side". The
 *  backend can serve any number of pollers cheaply; the runs table is indexed
 *  on (config_id, started_at DESC). 2.5s strikes a balance between perceived
 *  latency and request volume for a wizard that's only open during the run. */
const POLL_INTERVAL_MS = 2500;

@Component({
  selector: 'app-generate-billing-wizard',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent, CurrencyFormatPipe],
  templateUrl: './generate-billing-wizard.component.html',
  styleUrl: './generate-billing-wizard.component.scss',
})
export class GenerateBillingWizardComponent implements OnInit, OnDestroy {
  step: WizardStep = 'filters';
  errorMessage: string | null = null;
  /** True while the preview job is running in the background. */
  loading = false;
  /** True while the commit job is running in the background. */
  saving = false;
  /** Elapsed seconds since the current background job started — shown
   *  alongside the spinner so the user knows it's still alive. */
  elapsedSeconds = 0;

  /**
   * Billing month in {@code YYYY-MM} form (the value an
   * {@code &lt;input type="month"&gt;} produces). The wizard works one
   * calendar month at a time — every invoice is marked by month and there
   * is exactly one invoice per entity per month. {@link periodStart} and
   * {@link periodEnd} are derived from this value before being sent to the
   * backend so the existing API contract is untouched.
   */
  billingMonth = '';

  /**
   * Hard ceiling exposed to the &lt;input type="month"&gt; max attribute.
   * Billing can never run for a month later than "next month" — generating
   * past that point would invoice members who haven't been around yet.
   */
  maxBillingMonth = '';

  /**
   * Anchor month — current calendar month — used to label "retrospect"
   * runs ("you're billing a month that's already passed").
   */
  currentMonth = '';

  /** Derived period bounds, recomputed from {@link billingMonth} on submit. */
  periodStart = '';
  periodEnd = '';

  preview: BillingPreviewResponse | null = null;
  committedAt: string | null = null;
  committedCount = 0;
  committedTotals: Record<string, string> = {};
  committedGroupInvoices = 0;
  committedIndividualInvoices = 0;
  committedMembershipModel = '';

  private pollSubscription: Subscription | null = null;
  private jobStartMs = 0;

  /**
   * Tenant's enabled insurance lines (HEALTH | VEHICLE | PROPERTY | LIFE
   * | FUNERAL | TRAVEL | DISABILITY | GROUP). Single-line tenants see
   * the wizard with no tabs (legacy flow). Multi-line tenants get a tab
   * per enabled line — each tab is the same wizard skeleton but pins
   * the active line into the enqueue payload.
   */
  availableLines: string[] = [];
  activeLine = '';

  /**
   * Set when the last enqueueBilling job failed with a "period already
   * committed" error. Drives the inline "Revoke and regenerate" card
   * — visible only when the operator has billing:revoke_billing AND
   * the offending period is in the next-month window.
   */
  alreadyCommittedDetail: {
    periodStart: string;
    periodEnd: string;
    insuranceLine: string | null;
    existingCount: number;
  } | null = null;
  revoking = false;

  constructor(
    private contributions: ContributionsService,
    private tenantSvc: TenantService,
    private perms: PermissionService,
  ) {}

  /** Permission gate for the revoke flow — hidden when the caller can't revoke. */
  get canRevoke(): boolean { return this.perms.hasAny(['billing:revoke_billing']); }

  /**
   * Backend rule duplicated client-side so the revoke button only
   * appears when revoke would actually succeed. periodStart must be
   * the first day of the calendar month immediately following today.
   */
  get revokeWindowActive(): boolean {
    if (!this.alreadyCommittedDetail) return false;
    const now = new Date();
    const next = new Date(now.getFullYear(), now.getMonth() + 1, 1);
    const expected = `${next.getFullYear().toString().padStart(4, '0')}-${(next.getMonth() + 1).toString().padStart(2, '0')}-01`;
    return this.alreadyCommittedDetail.periodStart === expected;
  }

  revoke(): void {
    if (!this.alreadyCommittedDetail) return;
    if (!confirm('Revoke and delete all contributions + invoices for this period? You\'ll need to re-commit to restore them.')) return;
    this.revoking = true;
    this.contributions.revokeBilling({
      periodStart:  this.alreadyCommittedDetail.periodStart,
      periodEnd:    this.alreadyCommittedDetail.periodEnd,
      insuranceLine: this.alreadyCommittedDetail.insuranceLine ?? this.activeLine,
    }).subscribe({
      next: (resp) => {
        this.revoking = false;
        this.alreadyCommittedDetail = null;
        this.errorMessage = `Revoked: deleted ${resp.contributionsDeleted} contribution(s) and ${resp.invoicesDeleted} invoice(s). Run commit again to regenerate.`;
      },
      error: (err) => {
        this.revoking = false;
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Revoke failed';
      },
    });
  }

  /** Friendly label for a line code — used in the tab strip + payload preview. */
  lineLabel(code: string): string { return LINE_LABELS[code] ?? code; }

  /** True when the wizard should render the tab strip (2+ lines enabled). */
  get showLineTabs(): boolean { return this.availableLines.length > 1; }

  /** Switch the active line tab. Resets preview/commit state so the new
   *  line's run starts from a clean filter step. */
  selectLine(line: string): void {
    if (this.activeLine === line) return;
    this.activeLine = line;
    this.startAnother();
  }

  ngOnInit(): void {
    const today = new Date();
    // Seed the tab strip from the tenant snapshot. Fall back to [HEALTH]
    // when the field is missing (older cached tenants predate the
    // insuranceLines settings array) so the legacy single-line flow
    // keeps working.
    const lines = this.tenantSvc.getTenant()?.insuranceLines ?? [];
    this.availableLines = lines.length > 0 ? lines : ['HEALTH'];
    this.activeLine = this.availableLines[0];
    // Default to NEXT month — that's the normal forward-billing flow
    // (we're in June → bill for July). The user can shift it earlier for
    // a retrospective run; the input's `max` keeps them from going past
    // next month, so we never invoice a period that hasn't begun yet.
    const nextMonthAnchor = new Date(today.getFullYear(), today.getMonth() + 1, 1);
    this.billingMonth    = this.toMonthValue(nextMonthAnchor);
    this.maxBillingMonth = this.billingMonth;
    this.currentMonth    = this.toMonthValue(new Date(today.getFullYear(), today.getMonth(), 1));
    this.recomputePeriodBounds();
  }

  /** YYYY-MM string the &lt;input type="month"&gt; produces and accepts. */
  private toMonthValue(d: Date): string {
    const year  = d.getFullYear().toString().padStart(4, '0');
    const month = (d.getMonth() + 1).toString().padStart(2, '0');
    return `${year}-${month}`;
  }

  /** Re-derive periodStart / periodEnd from the chosen month. periodEnd
   *  is the last day of the month (inclusive); built via the JS quirk
   *  that "day 0 of next month" === "last day of this month". */
  private recomputePeriodBounds(): void {
    if (!this.billingMonth) {
      this.periodStart = '';
      this.periodEnd   = '';
      return;
    }
    const [y, m] = this.billingMonth.split('-').map(n => Number(n));
    const start  = new Date(y, m - 1, 1);
    const end    = new Date(y, m, 0);  // day 0 of next month = last day of this one
    this.periodStart = this.toIsoDate(start);
    this.periodEnd   = this.toIsoDate(end);
  }

  private toIsoDate(d: Date): string {
    return [
      d.getFullYear().toString().padStart(4, '0'),
      (d.getMonth() + 1).toString().padStart(2, '0'),
      d.getDate().toString().padStart(2, '0'),
    ].join('-');
  }

  /** True when the user has shifted to a month earlier than the current one
   *  — the run will only bill clients/groups that were members during that
   *  month, surfaced as a banner in the template. */
  get isRetrospect(): boolean {
    return !!this.billingMonth && !!this.currentMonth && this.billingMonth < this.currentMonth;
  }

  /** Friendly label shown in the heading and the retrospect banner
   *  (e.g. "July 2026"). */
  get billingMonthLabel(): string {
    return this.formatMonth(this.billingMonth);
  }

  get maxBillingMonthLabel(): string {
    return this.formatMonth(this.maxBillingMonth);
  }

  private formatMonth(value: string): string {
    if (!value) return '';
    const [y, m] = value.split('-').map(n => Number(n));
    return new Date(y, m - 1, 1).toLocaleDateString(undefined, { month: 'long', year: 'numeric' });
  }

  ngOnDestroy(): void {
    this.stopPolling();
  }

  /** Friendly label for the tenant's membership model banner. */
  membershipBanner(model: string | undefined): string {
    switch (model) {
      case 'GROUP_ONLY':      return 'Groups only: every member must belong to a group; invoices go to liaisons.';
      case 'INDIVIDUAL_ONLY': return 'Individuals only: one invoice per member.';
      case 'BOTH':            return 'Mixed: grouped members billed via their group liaison; ungrouped members billed individually.';
      default:                return '';
    }
  }

  totalsKeys(map: Record<string, string> | undefined): string[] {
    return map ? Object.keys(map) : [];
  }

  /**
   * Group the preview's per-person sample rows into the invoices they
   * would roll into. Mirrors the backend routing rule:
   *   - INDIVIDUAL_ONLY → (memberId, currency)
   *   - GROUP_ONLY / BOTH → (groupId, currency) when groupId is set,
   *     otherwise fall back to (memberId, currency).
   * The fallback covers ungrouped members in a BOTH tenant and the
   * GROUP_ONLY anomaly path where someone has no group attached.
   */
  get sampleInvoices(): SampleInvoice[] {
    if (!this.preview) return [];
    const model = this.preview.membershipModel || 'BOTH';
    const buckets = new Map<string, SampleInvoice>();

    for (const row of this.preview.sample) {
      const useGroup = model !== 'INDIVIDUAL_ONLY' && !!row.groupId;
      const type: 'GROUP' | 'INDIVIDUAL' = useGroup ? 'GROUP' : 'INDIVIDUAL';
      const anchorId = useGroup ? row.groupId! : row.memberId;
      const routingKey = `${type}:${anchorId}:${row.currencyCode}`;

      let bucket = buckets.get(routingKey);
      if (!bucket) {
        // Holder name: group name if we have it, else the lead row's person
        // name. The lead row is whatever sample row hits this bucket first
        // — for an individual invoice that's the member's own line; for a
        // group invoice we'd rather show the group's name when available.
        const holderName = useGroup
          ? (row.groupName ?? `Group ${anchorId.slice(0, 8)}`)
          : (row.personType === 'MEMBER' ? row.personName : row.personName);
        bucket = { type, holderName, routingKey, currencyCode: row.currencyCode, lines: [], total: 0 };
        buckets.set(routingKey, bucket);
      }
      bucket.lines.push(row);
      const amt = Number(row.amount);
      if (Number.isFinite(amt)) bucket.total += amt;
    }

    // Sort each card's lines so families stay together: order by
    // memberNumber first (groups all rows belonging to one member),
    // then put the member's own line before its dependants. Keeps a
    // group invoice readable when N members live on the same card.
    for (const invoice of buckets.values()) {
      invoice.lines.sort((a, b) => {
        const byNumber = (a.memberNumber || '').localeCompare(b.memberNumber || '');
        if (byNumber !== 0) return byNumber;
        if (a.personType === b.personType) return 0;
        return a.personType === 'MEMBER' ? -1 : 1;
      });
    }
    return Array.from(buckets.values());
  }

  runPreview(): void {
    if (!this.billingMonth) {
      this.errorMessage = 'Pick a billing month';
      return;
    }
    if (this.billingMonth > this.maxBillingMonth) {
      // Belt-and-braces — the month input's max attribute already blocks
      // this in compliant browsers, but a hand-edited value could slip past.
      this.errorMessage = `Cannot bill beyond ${this.maxBillingMonthLabel} — next month is the latest allowed.`;
      return;
    }
    this.recomputePeriodBounds();
    this.loading = true;
    this.errorMessage = null;
    const payload: EnqueueBillingPayload = {
      kind: 'preview',
      periodStart: this.periodStart,
      periodEnd: this.periodEnd,
      // Single-line tenants leave activeLine = 'HEALTH' (the legacy
      // default) — sending it explicitly hardens the contract against
      // a future backend default change.
      insuranceLine: this.activeLine,
    };
    this.contributions.enqueueBilling(payload).subscribe({
      next: (resp) => {
        // The dispatcher today runs jobs synchronously inside runNow, so the
        // returned status is usually already SUCCESS — we still poll so the
        // UI works the same way once the dispatcher moves jobs onto a
        // worker thread pool. The first poll either picks up the result
        // immediately or finds RUNNING and continues.
        this.pollJob(resp.configId, 'preview');
      },
      error: (err) => {
        this.loading = false;
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Could not enqueue preview';
      },
    });
  }

  commit(): void {
    if (!this.preview) return;
    this.saving = true;
    this.errorMessage = null;
    const payload: EnqueueBillingPayload = {
      kind: 'commit',
      periodStart: this.periodStart,
      periodEnd: this.periodEnd,
      insuranceLine: this.activeLine,
    };
    this.contributions.enqueueBilling(payload).subscribe({
      next: (resp) => this.pollJob(resp.configId, 'commit'),
      error: (err) => {
        this.saving = false;
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Could not enqueue commit';
      },
    });
  }

  backToFilters(): void {
    this.step = 'filters';
    this.preview = null;
  }

  /**
   * Tab-strip navigation. Selecting Filters always works (re-edit). Preview
   * is only reachable when a preview is loaded. Committed is only reachable
   * after a successful commit. Disabled tabs aren't clickable in the
   * template, but this guard belt-and-braces against keyboard activation.
   */
  goToStep(target: WizardStep): void {
    if (target === 'preview' && !this.preview) return;
    if (target === 'committed' && !this.committedAt) return;
    this.step = target;
  }

  /** Whether each tab is available given the current wizard state. */
  isStepAvailable(target: WizardStep): boolean {
    if (target === 'filters')   return true;
    if (target === 'preview')   return !!this.preview;
    if (target === 'committed') return !!this.committedAt;
    return false;
  }

  startAnother(): void {
    this.step = 'filters';
    this.preview = null;
    this.committedAt = null;
    this.committedCount = 0;
    this.committedTotals = {};
    this.committedGroupInvoices = 0;
    this.committedIndividualInvoices = 0;
    this.committedMembershipModel = '';
  }

  // ── Polling ──────────────────────────────────────────────────────────────

  private pollJob(configId: string, kind: 'preview' | 'commit'): void {
    this.stopPolling();
    this.jobStartMs = Date.now();
    this.elapsedSeconds = 0;
    this.pollSubscription = interval(POLL_INTERVAL_MS).pipe(
      tap(() => { this.elapsedSeconds = Math.floor((Date.now() - this.jobStartMs) / 1000); }),
      switchMap(() => this.contributions.listJobRuns(configId, 1)),
      takeWhile((rows) => rows.length === 0 || rows[0].status === 'RUNNING', true),
    ).subscribe({
      next: (rows) => {
        if (rows.length === 0) return;
        const run = rows[0];
        if (run.status === 'RUNNING') return;
        this.stopPolling();
        if (run.status === 'FAILED') {
          this.handleFailure(run, kind);
        } else {
          this.handleSuccess(run, kind);
        }
      },
      error: () => {
        this.stopPolling();
        this.loading = false;
        this.saving = false;
        this.errorMessage = 'Lost track of the billing job — refresh and check the job runs page.';
      },
    });
  }

  private stopPolling(): void {
    this.pollSubscription?.unsubscribe();
    this.pollSubscription = null;
  }

  private handleSuccess(run: ScheduledJobRun, kind: 'preview' | 'commit'): void {
    if (!run.resultPayload) {
      this.loading = false;
      this.saving = false;
      this.errorMessage = 'Job finished but returned no result — check the runs log.';
      return;
    }
    try {
      const parsed = JSON.parse(run.resultPayload);
      if (kind === 'preview') {
        this.preview = parsed as BillingPreviewResponse;
        this.loading = false;
        this.step = 'preview';
      } else {
        const commit = parsed as BillingCommitResponse;
        this.saving = false;
        this.committedCount = commit.contributionsCreated;
        this.committedTotals = commit.totalsByCurrency;
        this.committedAt = commit.committedAt;
        this.committedGroupInvoices = commit.groupInvoicesCreated;
        this.committedIndividualInvoices = commit.individualInvoicesCreated;
        this.committedMembershipModel = commit.membershipModel;
        this.step = 'committed';
      }
    } catch {
      this.loading = false;
      this.saving = false;
      this.errorMessage = 'Could not parse the job result.';
    }
  }

  private handleFailure(run: ScheduledJobRun, kind: 'preview' | 'commit'): void {
    this.loading = false;
    this.saving = false;
    const label = kind === 'preview' ? 'Preview' : 'Commit';
    this.errorMessage = run.errorMessage ? `${label} failed: ${run.errorMessage}` : `${label} failed`;
    // Sniff the failure message for the "already committed" signature
    // so the wizard can surface the revoke card without a separate
    // structured-error envelope from the backend.
    if (kind === 'commit'
        && run.errorMessage
        && run.errorMessage.includes('Billing already committed')) {
      this.alreadyCommittedDetail = {
        periodStart:   this.periodStart,
        periodEnd:     this.periodEnd,
        insuranceLine: this.activeLine || null,
        existingCount: 0,
      };
    }
  }
}
