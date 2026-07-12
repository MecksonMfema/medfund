import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { catchError, forkJoin, of } from 'rxjs';
import {
  Claim,
  ClaimLine,
  ClaimStatus,
  ClaimsService,
  LineDecisionPayload,
} from '../../../../core/services/claims.service';
import { PermissionService } from '../../../../core/security/permission.service';
import {
  ClaimsConfigService,
  RejectionReason,
} from '../../../../core/services/claims-config.service';
import {
  BeneficiaryBenefitUtilization,
  BenefitUsageMode,
  ContributionsService,
  Scheme,
  SchemeBenefit,
  SchemeProductProfile,
} from '../../../../core/services/contributions.service';
import { Dependant, Member, MembersService } from '../../../../core/services/members.service';
import { Provider, ProvidersService } from '../../../../core/services/providers.service';
import { PreAuthService, PreAuthorization } from '../../../../core/services/pre-auth.service';
import { ConfirmService } from '../../../../shared/components/confirm-dialog/confirm.service';
import { ToastService } from '../../../../shared/components/toast/toast.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { StatCardComponent } from '../../../../shared/components/stat-card/stat-card.component';
import { HasPermissionDirective } from '../../../../shared/directives/has-permission.directive';
import { CurrencyFormatPipe } from '../../../../shared/pipes/currency-format.pipe';
import { HumanizePipe } from '../../../../shared/pipes/humanize.pipe';

/** In-flight decision for a single line while edit mode is active. */
interface LineDraft {
  status: 'PENDING' | 'ACCEPTED' | 'REJECTED';
  approvedAmount: string;
  rejectionReason: string;
  /** Adjudicator's tariff-code override. Seeded from the server's
   *  current value; changing it triggers the backend snapshot of the
   *  original on save. */
  tariffCode: string;
  modifierCodes: string;
}

@Component({
  selector: 'app-claim-detail',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    RouterLink,
    IconComponent,
    SelectComponent,
    SkeletonComponent,
    StatCardComponent,
    HasPermissionDirective,
    CurrencyFormatPipe,
    HumanizePipe,
  ],
  templateUrl: './claim-detail.component.html',
  styleUrl: './claim-detail.component.scss',
})
export class ClaimDetailComponent implements OnInit {
  claim: Claim | null = null;
  lines: ClaimLine[] = [];
  rejectionReasons: RejectionReason[] = [];
  member: Member | null = null;
  /** The dependant the claim was captured for, if any. Null when the
   *  claim's payload was the member themselves. */
  dependant: Dependant | null = null;
  provider: Provider | null = null;
  memberHistory: Claim[] = [];
  memberPreauths: PreAuthorization[] = [];
  benefits: SchemeBenefit[] = [];
  /** Per-benefit utilization for the beneficiary — used vs. limit for
   *  the current policy year. Empty when the beneficiary has no rows
   *  yet (new member enrolled after V060 backfill and before the
   *  enrolment hook ships). */
  utilization: BeneficiaryBenefitUtilization[] = [];
  /** Fetched alongside benefits so the member card can show the scheme
   *  name + line without needing the operator to hop to the schemes page. */
  scheme: Scheme | null = null;
  /** V061 — scheme + per-benefit usage-mode snapshot. Drives the header
   *  badge and whether the utilization card renders at all. */
  productProfile: SchemeProductProfile | null = null;
  /** V061 save-time balance breach reason, set from a 422 on
   *  applyLineDecisions. Rendered as an inline red banner in the
   *  service-lines card; cleared on the next save attempt. */
  saveBreachError: string | null = null;

  /** Convenience getters for the template so it doesn't repeatedly
   *  optional-chain through {@link scheme}. */
  get schemeName(): string | null { return this.scheme?.name ?? null; }
  get activeLine(): string | null {
    return this.scheme?.insuranceLine ?? this.claim?.insuranceLine ?? null;
  }

  loading = false;
  busy = false;
  rejectingCode = '';

  /** True when the current user can edit line decisions (adjudicator or
   *  super admin — {@link PermissionService.has} returns true for the
   *  latter automatically). */
  canEditLines = false;
  /** Per-line decision drafts, keyed by lineId. Populated on load from
   *  the server-side status; every write to a status pill or amount
   *  input updates this map. A single "Save decisions" button below the
   *  table batch-POSTs whichever rows the operator flipped. */
  lineDrafts: Record<string, LineDraft> = {};

  constructor(
    private claims: ClaimsService,
    private config: ClaimsConfigService,
    private contributions: ContributionsService,
    private members: MembersService,
    private providers: ProvidersService,
    private preauth: PreAuthService,
    private confirm: ConfirmService,
    private toast: ToastService,
    private permissions: PermissionService,
    private route: ActivatedRoute,
    private router: Router,
  ) {
    // Edit-line permission — the base claims:adjudicate right covers
    // regular adjudicators; PermissionService.has() also returns true
    // for super admins, so no separate super-admin check is needed here.
    this.canEditLines = this.permissions.has('claims:adjudicate');
  }

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (!id) {
      this.router.navigate(['/tenant/claims']);
      return;
    }
    this.load(id);
  }

  private load(id: string): void {
    this.loading = true;
    forkJoin({
      claim: this.claims.getById(id),
      lines: this.claims.getLines(id),
      reasons: this.config.listRejectionReasons(true).pipe(catchError(() => of([] as RejectionReason[]))),
    }).subscribe({
      next: ({ claim, lines, reasons }) => {
        this.claim = claim;
        this.lines = lines;
        this.seedLineDrafts();
        this.rejectionReasons = reasons;
        this.loading = false;
        this.loadRelated(claim);
      },
      error: (err) => {
        this.toast.error(err?.error?.detail || 'Failed to load claim');
        this.loading = false;
      },
    });
  }

  private loadRelated(claim: Claim): void {
    this.members.getById(claim.memberId)
      .pipe(catchError(() => of(null as Member | null)))
      .subscribe(m => { this.member = m; });

    // Dependant only when the claim was captured against one. Falls back
    // silently — a dependant that's been deleted or moved shouldn't hide
    // the rest of the page.
    if (claim.dependantId) {
      this.members.getDependantById(claim.dependantId)
        .pipe(catchError(() => of(null as Dependant | null)))
        .subscribe(d => { this.dependant = d; });
    }

    // Provider is nullable now (member-reimbursement claims). Only fetch
    // when there's actually a provider to look up.
    if (claim.providerId) {
      this.providers.getById(claim.providerId)
        .pipe(catchError(() => of(null as Provider | null)))
        .subscribe(p => { this.provider = p; });
    }

    this.claims.getByMember(claim.memberId)
      .pipe(catchError(() => of([] as Claim[])))
      .subscribe(cs => { this.memberHistory = cs.filter(c => c.id !== claim.id); });

    this.preauth.findByMember(claim.memberId)
      .pipe(catchError(() => of([] as PreAuthorization[])))
      .subscribe(ps => { this.memberPreauths = ps.slice(0, 10); });

    // Scheme benefits + name/line — surface what the beneficiary is
    // entitled to (limits, waiting periods, age gates) so the adjudicator
    // can eyeball the claim's line items without hopping to another page.
    if (claim.schemeId) {
      this.contributions.getSchemeById(claim.schemeId)
        .pipe(catchError(() => of(null as Scheme | null)))
        .subscribe(s => { this.scheme = s; });
      this.contributions.getBenefitsByScheme(claim.schemeId)
        .pipe(catchError(() => of([] as SchemeBenefit[])))
        .subscribe(bs => { this.benefits = bs; });
      this.contributions.getSchemeProductProfile(claim.schemeId)
        .pipe(catchError(() => of(null as SchemeProductProfile | null)))
        .subscribe(p => { this.productProfile = p; });
    }

    // Per-beneficiary utilization — used vs. limit per benefit for the
    // current policy year. Falls back to an empty list on error so a
    // failed lookup doesn't hide the rest of the page.
    this.contributions.getBeneficiaryUtilization(claim.memberId, claim.dependantId)
      .pipe(catchError(() => of([] as BeneficiaryBenefitUtilization[])))
      .subscribe(rows => { this.utilization = rows; });
  }

  beneficiaryName(): string {
    if (this.dependant) {
      return `${this.dependant.firstName} ${this.dependant.lastName}`.trim();
    }
    if (!this.member) return this.claim?.memberId ?? '';
    return `${this.member.firstName} ${this.member.lastName}`.trim() || this.member.memberNumber;
  }

  beneficiaryKind(): 'member' | 'dependant' {
    return this.dependant ? 'dependant' : 'member';
  }

  openClaim(id: string): void {
    if (!this.claim || id === this.claim.id) return;
    this.router.navigate(['/tenant/claims', id]);
  }

  get rejectionReasonOptions(): SelectOption[] {
    return this.rejectionReasons.map(r => ({
      value: r.code,
      label: r.code,
      description: r.description,
    }));
  }

  isLineShort(line: ClaimLine): boolean {
    if (!line.approvedAmount) return false;
    return Number(line.approvedAmount) < Number(line.claimedAmount);
  }

  anyShortAward(): boolean {
    return this.lines.some(l => this.isLineShort(l));
  }

  // ── Per-line adjudication ────────────────────────────────────────
  //
  // Drafts are seeded from the server-side state on load, so every row
  // is immediately editable for users who hold claims:adjudicate (or
  // super admin). No edit-mode toggle — the controls are just there.
  // Read-only viewers see the same layout with the buttons disabled.

  private seedLineDrafts(): void {
    this.lineDrafts = {};
    for (const line of this.lines) {
      this.lineDrafts[line.id] = {
        status: (line.status ?? 'PENDING') as LineDraft['status'],
        approvedAmount: line.approvedAmount ?? line.claimedAmount,
        rejectionReason: line.rejectionReason ?? '',
        // Seed through displayCodes so a stored "[]" (or JSON array
        // literal) doesn't leak into the input as text the operator
        // then has to hand-delete.
        tariffCode: this.displayCodes(line.tariffCode),
        modifierCodes: this.displayCodes(line.modifierCodes),
      };
    }
  }

  draftFor(line: ClaimLine): LineDraft {
    return this.lineDrafts[line.id] ??= {
      status: 'PENDING', approvedAmount: line.claimedAmount, rejectionReason: '',
      tariffCode: this.displayCodes(line.tariffCode),
      modifierCodes: this.displayCodes(line.modifierCodes),
    };
  }

  acceptLine(line: ClaimLine): void {
    if (!this.canEditLines) return;
    const draft = this.draftFor(line);
    draft.status = 'ACCEPTED';
    draft.rejectionReason = '';
    // Default the approved amount to the claimed amount when the
    // operator hasn't tweaked it yet. A zero-out means it was
    // previously REJECTED; snap it back to the full award.
    if (!draft.approvedAmount || Number(draft.approvedAmount) === 0) {
      draft.approvedAmount = line.claimedAmount;
    }
  }

  rejectLine(line: ClaimLine): void {
    if (!this.canEditLines) return;
    const draft = this.draftFor(line);
    draft.status = 'REJECTED';
    draft.approvedAmount = '0';
  }

  clearLineDecision(line: ClaimLine): void {
    if (!this.canEditLines) return;
    const draft = this.draftFor(line);
    draft.status = 'PENDING';
    draft.rejectionReason = '';
    draft.approvedAmount = line.approvedAmount ?? line.claimedAmount;
  }

  draftApprovedTotal(): number {
    return Object.values(this.lineDrafts)
      .filter(d => d.status === 'ACCEPTED')
      .reduce((s, d) => s + (Number(d.approvedAmount) || 0), 0);
  }

  /** Whether the operator has changed anything vs. the server state.
   *  The Save button stays disabled until the answer is yes. */
  hasPendingChanges(): boolean {
    for (const line of this.lines) {
      const d = this.lineDrafts[line.id];
      if (!d) continue;
      const serverStatus = (line.status ?? 'PENDING');
      const serverAmount = line.approvedAmount ?? line.claimedAmount;
      const serverReason = line.rejectionReason ?? '';
      if (d.status !== serverStatus) return true;
      if (d.status === 'ACCEPTED' && Number(d.approvedAmount) !== Number(serverAmount)) return true;
      if (d.status === 'REJECTED' && d.rejectionReason !== serverReason) return true;
      // Tariff / modifier edits count as pending changes even without
      // a status flip — the adjudicator may correct a code without
      // wanting to accept/reject yet.
      if ((d.tariffCode ?? '') !== (line.tariffCode ?? '')) return true;
      if ((d.modifierCodes ?? '') !== (line.modifierCodes ?? '')) return true;
    }
    return false;
  }

  /** Show the "originally X" annotation only when the current code
   *  differs from the snapshot. Uses displayCodes() so JSON-array
   *  originals render cleanly. */
  showOriginal(current: string | null | undefined, original: string | null | undefined): boolean {
    const c = (current ?? '').trim();
    const o = this.displayCodes(original);
    return !!o && o !== c;
  }

  async saveLineDecisions(): Promise<void> {
    if (!this.claim) return;
    const decisions: LineDecisionPayload[] = [];
    for (const line of this.lines) {
      const d = this.lineDrafts[line.id];
      if (!d) continue;
      // Include the line if its status changed OR the codes changed.
      // Skip untouched rows so the server doesn't churn on them.
      const statusChanged = d.status !== (line.status ?? 'PENDING') && d.status !== 'PENDING';
      const codesChanged =
        (d.tariffCode ?? '') !== (line.tariffCode ?? '') ||
        (d.modifierCodes ?? '') !== (line.modifierCodes ?? '');
      if (!statusChanged && !codesChanged) continue;
      if (d.status === 'REJECTED' && !d.rejectionReason) {
        this.toast.warning(`Pick a reason for line ${line.tariffCode || line.id}`);
        return;
      }
      decisions.push({
        lineId: line.id,
        // A code-only edit without a status flip keeps the current
        // status — send whatever the draft currently reads.
        status: (d.status === 'PENDING' ? (line.status ?? 'PENDING') : d.status) as 'ACCEPTED' | 'REJECTED',
        approvedAmount: d.status === 'ACCEPTED'
          ? (Number(d.approvedAmount) || 0).toFixed(2)
          : undefined,
        rejectionReason: d.status === 'REJECTED' ? d.rejectionReason : undefined,
        tariffCode: codesChanged ? d.tariffCode.trim() : undefined,
        modifierCodes: codesChanged ? d.modifierCodes.trim() : undefined,
      });
    }
    if (decisions.length === 0) {
      this.toast.warning('No changes to save.');
      return;
    }
    const ok = await this.confirm.ask({
      title: 'Apply line decisions',
      message: `Save ${decisions.length} line decision${decisions.length === 1 ? '' : 's'}? ` +
               `The claim's approved total will be recomputed and status moved to ADJUDICATED / REJECTED.`,
      confirmLabel: 'Save',
    });
    if (!ok) return;

    this.busy = true;
    this.saveBreachError = null;
    this.claims.applyLineDecisions(this.claim.id, decisions).subscribe({
      next: (updated) => {
        this.claim = updated;
        this.busy = false;
        this.toast.success(`Applied ${decisions.length} decision(s) — claim now ${updated.status}`);
        // Refresh lines so the newly-persisted status pills render and
        // re-seed drafts from the fresh server state.
        this.claims.getLines(updated.id).subscribe({
          next: (lines) => {
            this.lines = lines;
            this.seedLineDrafts();
          },
        });
        // Utilization row(s) just changed on the server — refresh so
        // the running-balance progress bar advances on the same page.
        if (this.claim) {
          this.contributions.getBeneficiaryUtilization(this.claim.memberId, this.claim.dependantId)
            .pipe(catchError(() => of([] as BeneficiaryBenefitUtilization[])))
            .subscribe(rows => { this.utilization = rows; });
        }
      },
      error: (err) => {
        this.busy = false;
        // V061 — surface a 422 save-time breach as an inline banner in
        // the service-lines card so the adjudicator can lower amounts
        // and retry without navigating away.
        if (err?.status === 422) {
          this.saveBreachError = err?.error?.detail || err?.error?.message
                                || 'Approved total exceeds the beneficiary\'s remaining balance for this benefit.';
        } else {
          this.toast.error(err?.error?.detail || 'Failed to save line decisions');
        }
      },
    });
  }

  memberName(): string {
    if (!this.member) return this.claim?.memberId ?? '';
    return `${this.member.firstName} ${this.member.lastName}`.trim() || this.member.memberNumber;
  }

  /**
   * Cleans up code-list fields (diagnosis / procedure) for display.
   * Historically the columns were jsonb and rows may still carry a
   * literal "[]" or a JSON array string like `["J18.9","I10"]`. Convert
   * both to a clean comma-separated string, or an empty string so the
   * KV row can hide itself.
   */
  /** Age at the claim's service date — derived from the beneficiary's
   *  DOB. Returns null when either input is missing so the template
   *  hides the KV row rather than showing "NaN". */
  ageAtService(dob: string | null | undefined): number | null {
    if (!dob || !this.claim?.serviceDate) return null;
    const born = new Date(dob);
    const service = new Date(this.claim.serviceDate);
    if (isNaN(born.getTime()) || isNaN(service.getTime())) return null;
    let age = service.getFullYear() - born.getFullYear();
    const m = service.getMonth() - born.getMonth();
    if (m < 0 || (m === 0 && service.getDate() < born.getDate())) age--;
    return age >= 0 ? age : null;
  }

  /** Percent of annual limit consumed (0–100). Returns null when the
   *  benefit has no annual limit so the template can render an "unlimited"
   *  chip instead of a progress bar. */
  utilizationPct(row: BeneficiaryBenefitUtilization): number | null {
    const limit = Number(row.annualLimit);
    if (!limit || limit <= 0) return null;
    const used = Number(row.consumedAmount) || 0;
    return Math.min(100, Math.round((used / limit) * 100));
  }

  /** V061 — whether the utilization card should render at all. Hidden
   *  when the scheme opts out of per-member ledger tracking. */
  get showUtilization(): boolean {
    return this.productProfile?.tracksMemberBalances !== false;
  }

  /** V061 — filter NO_TRACKING benefits out of the utilization list so
   *  the operator only sees rows that actually enforce a limit. */
  get visibleUtilization(): BeneficiaryBenefitUtilization[] {
    return this.utilization.filter(row => row.usageMode !== 'NO_TRACKING');
  }

  /** V061 — reasonable defaults for pre-V061 responses that don't
   *  populate usageMode. Falls back to RUNNING_BALANCE so the UI keeps
   *  its existing behaviour. */
  usageModeOf(row: BeneficiaryBenefitUtilization): BenefitUsageMode {
    return row.usageMode ?? 'RUNNING_BALANCE';
  }

  /** V061 — humanized label for the header product-type chip. */
  productTypeLabel(): string {
    if (!this.productProfile) return '';
    return this.productProfile.tracksMemberBalances ? 'Ledger tracked' : 'Scheme-level only';
  }

  displayCodes(raw: string | null | undefined): string {
    if (!raw) return '';
    const s = raw.trim();
    if (!s || s === '[]' || s === '{}' || s === 'null') return '';
    if (s.startsWith('[')) {
      try {
        const arr = JSON.parse(s);
        if (Array.isArray(arr)) return arr.filter(Boolean).join(', ');
      } catch { /* fall through */ }
    }
    return s;
  }

  hasDisplayValue(raw: string | null | undefined): boolean {
    return this.displayCodes(raw).length > 0;
  }

  providerName(): string {
    if (this.provider) return this.provider.name;
    // Explicitly signal a member-reimbursement claim — clearer for the
    // adjudicator than an empty provider slot.
    if (this.claim && !this.claim.providerId) return 'Member reimbursement';
    return this.claim?.providerId ?? '';
  }

  // ── Lifecycle actions ────────────────────────────────────────────────

  adjudicate(): void {
    if (!this.claim) return;
    this.busy = true;
    this.claims.adjudicate(this.claim.id).subscribe({
      next: (updated) => {
        this.claim = updated;
        this.busy = false;
        this.toast.success(`Pipeline complete — status now ${updated.status}`);
        this.claims.getLines(updated.id).subscribe({ next: (lines) => (this.lines = lines) });
      },
      error: (err) => {
        this.busy = false;
        this.toast.error(err?.error?.detail || 'Adjudication failed');
      },
    });
  }

  async approve(): Promise<void> {
    if (!this.claim) return;
    const ok = await this.confirm.ask({
      title: 'Approve claim',
      message: `Mark ${this.claim.claimNumber} as ADJUDICATED (approved at ${this.claim.claimedAmount} ${this.claim.currencyCode})?`,
      confirmLabel: 'Approve',
    });
    if (!ok) return;
    this.transition('ADJUDICATED');
  }

  async reject(): Promise<void> {
    if (!this.claim) return;
    if (!this.rejectingCode) {
      this.toast.warning('Pick a rejection reason first');
      return;
    }
    const ok = await this.confirm.ask({
      title: 'Reject claim',
      message: `Reject ${this.claim.claimNumber} with reason ${this.rejectingCode}?`,
      confirmLabel: 'Reject',
      danger: true,
    });
    if (!ok) return;
    this.transition('REJECTED');
  }

  async cancel(): Promise<void> {
    if (!this.claim) return;
    const ok = await this.confirm.ask({
      title: 'Cancel claim',
      message: `Cancel ${this.claim.claimNumber}? This is irreversible.`,
      confirmLabel: 'Cancel claim',
      danger: true,
    });
    if (!ok) return;
    this.transition('CANCELLED');
  }

  private transition(status: ClaimStatus): void {
    if (!this.claim) return;
    this.busy = true;
    this.claims.updateStatus(this.claim.id, status).subscribe({
      next: (updated) => {
        this.claim = updated;
        this.busy = false;
        this.toast.success(`Status updated to ${updated.status}`);
      },
      error: (err) => {
        this.busy = false;
        this.toast.error(err?.error?.detail || 'Status update failed');
      },
    });
  }

  canAct(action: 'adjudicate' | 'approve' | 'reject' | 'cancel'): boolean {
    if (!this.claim) return false;
    const s = this.claim.status;
    switch (action) {
      case 'adjudicate': return s === 'VERIFIED';
      case 'approve':    return s === 'IN_ADJUDICATION' || s === 'PENDING_INFO';
      case 'reject':     return s === 'IN_ADJUDICATION' || s === 'PENDING_INFO' || s === 'VERIFIED';
      case 'cancel':     return s !== 'PAID' && s !== 'CANCELLED';
      default:           return false;
    }
  }
}
// touched 1783780667
