import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Subscription, interval, switchMap, takeWhile, tap } from 'rxjs';
import {
  BillingCommitResponse,
  BillingPreviewResponse,
  ContributionsService,
  EnqueueBillingPayload,
  ScheduledJobRun,
} from '../../../../core/services/contributions.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { CurrencyFormatPipe } from '../../../../shared/pipes/currency-format.pipe';

type WizardStep = 'filters' | 'preview' | 'committed';

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

  constructor(private contributions: ContributionsService) {}

  ngOnInit(): void {
    const today = new Date();
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
  }
}
