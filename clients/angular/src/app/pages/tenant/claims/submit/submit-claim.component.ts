import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { Subject, debounceTime, distinctUntilChanged, of, switchMap } from 'rxjs';
import {
  Claim,
  ClaimAttachment,
  ClaimsService,
  ClaimSubmissionResponse,
  SubmitClaimPayload,
} from '../../../../core/services/claims.service';
import {
  ClaimsConfigService,
  TariffCode,
  TariffModifier,
} from '../../../../core/services/claims-config.service';
import { ContributionsService, Scheme } from '../../../../core/services/contributions.service';
import { CurrencyService, TenantCurrencyConfig } from '../../../../core/services/currency.service';
import { MembersService } from '../../../../core/services/members.service';
import { TenantService } from '../../../../core/services/tenant.service';
import {
  ClaimFieldKey,
  ProviderMode,
  claimFieldsForLine,
  hasClaimField,
  providerModeForLine,
  usesLineItems,
} from '../../../../core/models/insurance-lines';
import {
  EntityPickerComponent,
  EntityPickerSelection,
} from '../../../../shared/components/entity-picker/entity-picker.component';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';
import { StatCardComponent } from '../../../../shared/components/stat-card/stat-card.component';
import { ToastService } from '../../../../shared/components/toast/toast.service';
import { CurrencyFormatPipe } from '../../../../shared/pipes/currency-format.pipe';

interface LineDraft {
  tariffCode: string;
  description: string;
  quantity: number;
  unitPrice: string;
  modifierCodes: string;
}

/**
 * A staged attachment — the operator has picked the file but it hasn't
 * been sent to storage yet. We keep the actual {@link File} handle in
 * memory so a follow-up story can PUT it to the file-service presigned
 * URL flow; the submit payload only carries the metadata for now.
 */
interface StagedAttachment {
  file: File;
  filename: string;
  contentType: string;
  sizeBytes: number;
}

/** Cap on file size (5 MB) — protects the browser tab from an operator
 *  attaching a 200 MB scan by accident. */
const MAX_ATTACHMENT_BYTES = 5 * 1024 * 1024;

/**
 * Line-of-business-aware claim capture form. The user picks member,
 * provider, and scheme; the scheme drives the insurance line, which
 * drives which sub-form renders (line items for HEALTH/GROUP/TRAVEL;
 * single-total plus attribute fields for VEHICLE/PROPERTY/…).
 *
 * <p>The backend echoes back a {@link ClaimSubmissionResponse} envelope
 * containing the verification code, its 5-minute window, and the batch
 * number — surfaced inline via the confirmation strip.
 */
@Component({
  selector: 'app-submit-claim',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    EntityPickerComponent,
    IconComponent,
    SelectComponent,
    StatCardComponent,
    CurrencyFormatPipe,
  ],
  templateUrl: './submit-claim.component.html',
  styleUrl: './submit-claim.component.scss',
})
export class SubmitClaimComponent implements OnInit {
  saving = false;
  formError: string | null = null;
  submittedClaim: Claim | null = null;
  submittedBatchNumber: string | null = null;

  claimType: 'medical' | 'drug' | 'credit' = 'medical';

  // Selected entity IDs. The beneficiary picker may pick a dependant,
  // in which case dependantId carries the dep ID and memberId carries
  // the sponsoring member's ID (backend expects the sponsor as the
  // primary keyed member on the claim). For a member pick, dependantId
  // stays null.
  memberId: string | null = null;
  dependantId: string | null = null;
  /** Beneficiary picker's own value handle (member id or dependant id).
   *  Kept separate from memberId so the picker's chip shows the right
   *  name when a dependant is picked (the sponsor's chip would confuse). */
  beneficiaryId: string | null = null;
  providerId: string | null = null;
  /** Derived from the picked beneficiary's member row — never a user
   *  input. Null until a beneficiary is picked and their member is
   *  resolved; explicitly nulled when the member has no scheme yet
   *  (see {@link schemeStatus}). */
  schemeId: string | null = null;
  schemeName: string | null = null;
  /** UI status for the derived-scheme cell. */
  schemeStatus: 'idle' | 'loading' | 'ok' | 'missing' | 'error' = 'idle';

  // Reference data.
  currencies: TenantCurrencyConfig[] = [];
  modifiers: TariffModifier[] = [];

  // Header form fields.
  form = {
    serviceDate: new Date().toISOString().slice(0, 10),
    currencyCode: 'USD',
    diagnosisCodes: '',
    procedureCodes: '',
    notes: '',
    // Batching is opt-in. When {@link isBatched} is false, batchNumber
    // is not sent — the server persists null. Never auto-generated.
    isBatched: false,
    batchNumber: '',

    // Line-specific header fields — the template renders whichever ones
    // the derived insurance line calls for via {@link hasClaimField}.
    vehicleRegistration: '',
    incidentLocation: '',
    incidentReportRef: '',
    policeReportRef: '',
    propertyAddress: '',
    deathCertificateRef: '',
    deceasedRelationship: '',
    travelDestination: '',
    travelStartDate: '',
    travelEndDate: '',
    disabilityAssessmentRef: '',
    lifeCertificateRef: '',
    singleClaimedAmount: '',
  };

  lines: LineDraft[] = [this.emptyLine()];

  /** Files the operator has picked to attach. Metadata rides on the
   *  submit payload; byte upload wires in once file-service storage is
   *  configured for real (currently uses MockStorage). */
  attachments: StagedAttachment[] = [];

  // Adaptive-form state — recomputed whenever the picked scheme changes.
  activeLine: string | null = null;
  activeLineFields: ReadonlySet<ClaimFieldKey> = claimFieldsForLine(null);
  usesItemLines = true;
  /** Per-line rule for the provider field. FORBIDDEN hides the picker
   *  outright (payout goes to the member); OPTIONAL surfaces the opt-in
   *  toggle so the operator can attach a provider or leave the claim
   *  as a member reimbursement. */
  providerMode: ProviderMode = 'OPTIONAL';
  /** For OPTIONAL lines, gates whether the provider picker is shown. */
  providerOptIn = false;

  // Tariff-code autocomplete state (only relevant for line-item bodies).
  activeLineIndex: number | null = null;
  tariffMatches: TariffCode[] = [];
  tariffSearching = false;
  private tariffQuery$ = new Subject<string>();

  get currencyOptions(): SelectOption[] {
    return this.currencies.map(c => ({
      value: c.currencyCode,
      label: c.currencyCode,
      description: c.isDefault ? 'Default' : undefined,
    }));
  }

  get modifierOptions(): SelectOption[] {
    return this.modifiers.map(m => ({ value: m.code, label: m.code, description: m.name }));
  }

  get pageTitle(): string {
    return this.claimType === 'credit' ? 'Submit credit claim' : 'Submit claim';
  }

  get pageSub(): string {
    return this.claimType === 'credit'
      ? 'Reverse or refund a previously-processed claim. Records under the same lifecycle as a regular claim, but flagged so downstream reporting can distinguish it.'
      : 'Capture a claim on behalf of a member. The form adapts to the picked scheme’s insurance line; on submit a verification code is returned that the member reads back to move the claim into adjudication.';
  }

  constructor(
    private claims: ClaimsService,
    private config: ClaimsConfigService,
    private contributions: ContributionsService,
    private currencyService: CurrencyService,
    private members: MembersService,
    private tenantService: TenantService,
    private toast: ToastService,
    private route: ActivatedRoute,
    private router: Router,
  ) {}

  ngOnInit(): void {
    const preset = this.route.snapshot.data?.['presetClaimType'];
    if (preset === 'drug' || preset === 'credit') this.claimType = preset;

    const tenant = this.tenantService.getTenant();
    if (tenant) {
      this.currencyService.listForTenant(tenant.id).subscribe({
        next: (rows) => {
          this.currencies = rows.filter(c => c.isActive);
          const def = this.currencies.find(c => c.isDefault);
          if (def) this.form.currencyCode = def.currencyCode;
        },
      });
    }

    this.config.listModifiers().subscribe({
      next: (rows) => { this.modifiers = rows; },
    });

    this.tariffQuery$
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        switchMap((q) => {
          const trimmed = q.trim();
          if (!trimmed) { this.tariffSearching = false; return of<TariffCode[]>([]); }
          this.tariffSearching = true;
          return this.config.searchCodes(trimmed);
        }),
      )
      .subscribe({
        next: (rows) => { this.tariffMatches = rows.slice(0, 8); this.tariffSearching = false; },
        error: () => { this.tariffMatches = []; this.tariffSearching = false; },
      });
  }

  // ── Scheme → insurance line resolution ───────────────────────────
  //
  // Whenever the picked scheme changes, refetch the scheme detail and
  // re-derive the active insurance line + field set. The backend
  // re-derives the same line at submit time (authoritative), but the
  // UI needs it inline to hide/show the right sections and prevent
  // the operator from filling a section that will be rejected.

  // ── Beneficiary pick → route to memberId / dependantId + resolve scheme ─
  onBeneficiaryPicked(sel: EntityPickerSelection | null): void {
    this.beneficiaryId = sel?.id ?? null;
    if (!sel) {
      this.memberId = null;
      this.dependantId = null;
      this.clearScheme();
      return;
    }
    const b = sel.beneficiary;
    if (!b) {
      // Should never happen when kind='beneficiary'; be defensive so a
      // future picker-kind change doesn't silently drop the claim's
      // memberId (which would result in a 400 at submit).
      this.memberId = sel.id;
      this.dependantId = null;
    } else {
      this.memberId = b.memberId;
      this.dependantId = b.dependantId;
    }
    this.resolveSchemeForMember(this.memberId!);
  }

  private clearScheme(): void {
    this.schemeId = null;
    this.schemeName = null;
    this.schemeStatus = 'idle';
    this.setActiveLine(null);
  }

  /**
   * Look up the member's scheme (dependants share their sponsor's, and
   * memberId here is always the sponsor). Snap the form's currency to
   * whatever the scheme is priced in and drive the adaptive body via
   * the scheme's insurance line. Sets schemeStatus so the template can
   * render a clear state — idle / loading / ok / missing / error.
   */
  private resolveSchemeForMember(memberId: string): void {
    this.schemeStatus = 'loading';
    this.members.getById(memberId).subscribe({
      next: (member) => {
        if (!member.schemeId) {
          this.schemeStatus = 'missing';
          this.schemeId = null;
          this.schemeName = null;
          this.setActiveLine(null);
          return;
        }
        this.contributions.getSchemeById(member.schemeId).subscribe({
          next: (scheme) => {
            this.schemeId = scheme.id;
            this.schemeName = scheme.name;
            this.schemeStatus = 'ok';
            this.setActiveLine(scheme.insuranceLine ?? 'HEALTH');
            if (scheme.currencyCode) this.form.currencyCode = scheme.currencyCode;
          },
          error: () => {
            this.schemeStatus = 'error';
            this.schemeId = null;
            this.schemeName = null;
            this.setActiveLine(null);
          },
        });
      },
      error: () => {
        this.schemeStatus = 'error';
        this.schemeId = null;
        this.schemeName = null;
        this.setActiveLine(null);
      },
    });
  }

  /**
   * Direct scheme override — bypasses the beneficiary-driven resolution.
   * The picker was removed from the template because the beneficiary's
   * scheme is authoritative, but the hook stays so a future "capture on
   * a different scheme than the member's default" story can wire it up
   * without another rewrite. Kept public because the spec suite uses it
   * as a shortcut past the member/scheme two-step.
   */
  onSchemeIdChange(id: string | null): void {
    this.schemeId = id;
    if (!id) { this.clearScheme(); return; }
    this.schemeStatus = 'loading';
    this.contributions.getSchemeById(id).subscribe({
      next: (scheme) => {
        this.schemeName = scheme.name;
        this.schemeStatus = 'ok';
        this.setActiveLine(scheme.insuranceLine ?? 'HEALTH');
        if (scheme.currencyCode) this.form.currencyCode = scheme.currencyCode;
      },
      error: () => {
        this.schemeStatus = 'error';
        this.schemeName = null;
        this.setActiveLine('HEALTH');
      },
    });
  }

  private setActiveLine(line: string | null): void {
    this.activeLine = line;
    this.activeLineFields = claimFieldsForLine(line);
    this.usesItemLines = usesLineItems(line);
    this.providerMode = providerModeForLine(line);
    // A FORBIDDEN switch must scrub any provider the operator picked
    // under the previous scheme — leaving it in place would silently
    // ride into the payload and be rejected 400 at submit.
    if (this.providerMode === 'FORBIDDEN') {
      this.providerId = null;
      this.providerOptIn = false;
    }
    // If we just switched from a line-item to a single-item body (or
    // vice versa), reset the corresponding entries so the payload is
    // clean and any leftover values from the previous scheme don't
    // ride through.
    if (this.usesItemLines) {
      this.form.singleClaimedAmount = '';
      if (this.lines.length === 0) this.lines = [this.emptyLine()];
    } else {
      this.lines = [];
    }
  }

  /** True when the provider picker should render on the form. FORBIDDEN
   *  hides it outright; OPTIONAL reveals it only after the operator
   *  ticks the opt-in toggle (default state: member-paid claim). */
  showProviderPicker(): boolean {
    if (this.providerMode === 'FORBIDDEN') return false;
    return this.providerOptIn;
  }

  hasField(key: ClaimFieldKey): boolean {
    return hasClaimField(this.activeLine, key);
  }

  // ── Line items ───────────────────────────────────────────────────
  private emptyLine(): LineDraft {
    return { tariffCode: '', description: '', quantity: 1, unitPrice: '', modifierCodes: '' };
  }

  addLine(): void {
    // Refuse to push a new blank row until the last one has enough to
    // survive submit — every empty row past the first is either a
    // silent no-op or an operator mis-click. Surface the constraint
    // as a toast rather than a hidden guard so the user knows why.
    const last = this.lines[this.lines.length - 1];
    if (last && (!last.tariffCode.trim() || !last.unitPrice)) {
      this.toast.warning('Fill in the current line before adding another.');
      return;
    }
    this.lines.push(this.emptyLine());
  }

  removeLine(i: number): void {
    if (this.lines.length === 1) return;
    this.lines.splice(i, 1);
    if (this.activeLineIndex === i) this.activeLineIndex = null;
  }

  onTariffCodeFocus(i: number): void {
    this.activeLineIndex = i;
    if (this.lines[i].tariffCode.trim()) this.tariffQuery$.next(this.lines[i].tariffCode);
  }

  onTariffCodeInput(i: number): void {
    this.activeLineIndex = i;
    this.tariffQuery$.next(this.lines[i].tariffCode);
  }

  onTariffCodeBlur(): void {
    setTimeout(() => { this.activeLineIndex = null; this.tariffMatches = []; }, 150);
  }

  pickTariff(i: number, code: TariffCode): void {
    const line = this.lines[i];
    line.tariffCode = code.code;
    if (!line.description) line.description = code.description;
    if (!line.unitPrice)   line.unitPrice   = String(code.unitPrice);
    this.activeLineIndex = null;
    this.tariffMatches = [];
  }

  toggleModifier(i: number, code: string): void {
    const line = this.lines[i];
    const codes = line.modifierCodes.split(',').map(s => s.trim()).filter(Boolean);
    const idx = codes.indexOf(code);
    if (idx >= 0) codes.splice(idx, 1);
    else codes.push(code);
    line.modifierCodes = codes.join(', ');
  }

  hasModifier(line: LineDraft, code: string): boolean {
    return line.modifierCodes.split(',').map(s => s.trim()).includes(code);
  }

  lineSubtotal(line: LineDraft): number {
    const qty = Number(line.quantity) || 0;
    const price = Number(line.unitPrice) || 0;
    return qty * price;
  }

  totalAmount(): number {
    if (this.usesItemLines) return this.lines.reduce((sum, l) => sum + this.lineSubtotal(l), 0);
    return Number(this.form.singleClaimedAmount) || 0;
  }

  lineCount(): number {
    return this.lines.filter(l => l.tariffCode.trim() && l.unitPrice).length;
  }

  // ── Attachments ──────────────────────────────────────────────────

  onAttachmentPick(event: Event): void {
    const input = event.target as HTMLInputElement;
    const picked = input.files;
    if (!picked || picked.length === 0) return;
    for (let i = 0; i < picked.length; i++) {
      const f = picked.item(i);
      if (!f) continue;
      if (f.size > MAX_ATTACHMENT_BYTES) {
        this.toast.warning(`${f.name} is over 5 MB and was skipped.`);
        continue;
      }
      // Reject duplicates by (filename, size) — the operator almost
      // certainly meant to add different files, not the same one twice.
      const dup = this.attachments.some(a => a.filename === f.name && a.sizeBytes === f.size);
      if (dup) continue;
      this.attachments.push({
        file: f,
        filename: f.name,
        contentType: f.type || 'application/octet-stream',
        sizeBytes: f.size,
      });
    }
    // Reset the input so the same file can be re-picked after removal.
    input.value = '';
  }

  removeAttachment(i: number): void {
    this.attachments.splice(i, 1);
  }

  formatBytes(n: number): string {
    if (n < 1024) return `${n} B`;
    if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
    return `${(n / (1024 * 1024)).toFixed(1)} MB`;
  }

  // ── Submit ───────────────────────────────────────────────────────
  submit(): void {
    this.formError = null;
    if (!this.memberId)   { this.formError = 'Pick a beneficiary'; return; }
    if (this.providerMode === 'FORBIDDEN' && this.providerId) {
      this.formError = `${this.activeLine} claims are paid to the member — remove the provider`;
      return;
    }
    if (!this.schemeId)   { this.formError = 'Pick a scheme'; return; }
    if (!this.activeLine) { this.formError = 'Scheme has no insurance line — pick a different scheme'; return; }
    if (!this.form.serviceDate) { this.formError = 'Service date is required'; return; }

    const total = this.totalAmount();
    if (total <= 0) {
      this.formError = this.usesItemLines
        ? 'Add at least one line item with a tariff code and unit price'
        : 'Enter the claimed amount';
      return;
    }
    // Per-line required fields — mirrors ClaimService.validateLineRequirements()
    // server-side. The UI check is here for immediate feedback; the server
    // still enforces it authoritatively.
    const missing = this.missingRequiredFieldFor(this.activeLine);
    if (missing) {
      this.formError = `${this.activeLine} claims require ${missing}`;
      return;
    }

    const payload: SubmitClaimPayload = {
      memberId: this.memberId!,
      dependantId: this.dependantId ?? undefined,
      providerId: this.providerId ?? undefined,
      schemeId: this.schemeId!,
      claimType: this.claimType,
      insuranceLine: this.activeLine!,
      // Only send batchNumber when the operator explicitly opted in.
      batchNumber: this.form.isBatched && this.form.batchNumber.trim()
        ? this.form.batchNumber.trim()
        : undefined,
      serviceDate: this.form.serviceDate,
      claimedAmount: total.toFixed(2),
      currencyCode: this.form.currencyCode,
      diagnosisCodes: this.form.diagnosisCodes.trim() || undefined,
      procedureCodes: this.form.procedureCodes.trim() || undefined,
      notes: this.form.notes.trim() || undefined,
      lines: this.usesItemLines
        ? this.lines
            .filter(l => l.tariffCode.trim() && l.unitPrice)
            .map(l => ({
              tariffCode: l.tariffCode.trim().toUpperCase(),
              description: l.description.trim() || undefined,
              quantity: Number(l.quantity) || 1,
              unitPrice: l.unitPrice,
              claimedAmount: this.lineSubtotal(l).toFixed(2),
              modifierCodes: l.modifierCodes.trim() || undefined,
              currencyCode: this.form.currencyCode,
            }))
        : undefined,

      vehicleRegistration:     this.pick('vehicleRegistration'),
      incidentLocation:        this.pick('incidentLocation'),
      incidentReportRef:       this.pick('incidentReport'),
      policeReportRef:         this.pick('policeReport'),
      propertyAddress:         this.pick('propertyAddress'),
      deathCertificateRef:     this.pick('deathCertificate'),
      deceasedRelationship:    this.pick('deceasedRelationship'),
      travelDestination:       this.pick('travelDestination'),
      travelStartDate:         this.pickDates('travelDates', 'start'),
      travelEndDate:           this.pickDates('travelDates', 'end'),
      disabilityAssessmentRef: this.pick('disabilityAssessment'),
      lifeCertificateRef:      this.pick('lifeCertificate'),
      attachments: this.attachments.length
        ? this.attachments.map<ClaimAttachment>(a => ({
            filename: a.filename,
            contentType: a.contentType,
            sizeBytes: a.sizeBytes,
          }))
        : undefined,
    };

    this.saving = true;
    this.claims.submit(payload).subscribe({
      next: (response: ClaimSubmissionResponse) => {
        this.saving = false;
        this.submittedClaim = response.claim;
        this.submittedBatchNumber = response.batchNumber;
        this.toast.success(`Claim ${response.claim.claimNumber} submitted.`);
      },
      error: (err) => {
        this.saving = false;
        const msg = err?.error?.detail || err?.error?.title || 'Submission failed';
        this.formError = msg;
        this.toast.error(msg);
      },
    });
  }

  /**
   * Returns the human-readable name of the missing required field for
   * the given line, or null if everything's present.
   */
  private missingRequiredFieldFor(line: string): string | null {
    switch (line) {
      case 'VEHICLE':
        if (!this.form.vehicleRegistration.trim()) return 'vehicle registration';
        if (!this.form.incidentLocation.trim())    return 'incident location';
        return null;
      case 'PROPERTY':
        if (!this.form.propertyAddress.trim())  return 'property address';
        if (!this.form.incidentLocation.trim()) return 'incident location';
        return null;
      case 'FUNERAL':
        if (!this.form.deathCertificateRef.trim()) return 'death certificate reference';
        return null;
      case 'LIFE':
        if (!this.form.lifeCertificateRef.trim()) return 'life certificate reference';
        return null;
      case 'DISABILITY':
        if (!this.form.disabilityAssessmentRef.trim()) return 'disability assessment reference';
        return null;
      default:
        return null;
    }
  }

  private pick(key: ClaimFieldKey): string | undefined {
    if (!this.hasField(key)) return undefined;
    // Map the field-key back to the header form's actual property name.
    // Keys and property names match 1:1 for these — kept as separate
    // string constants to avoid a leaky record type.
    const v = (this.form as unknown as Record<string, unknown>)[key] as string | undefined;
    return v && v.trim() ? v.trim() : undefined;
  }

  private pickDates(key: ClaimFieldKey, side: 'start' | 'end'): string | undefined {
    if (!this.hasField(key)) return undefined;
    const prop = side === 'start' ? 'travelStartDate' : 'travelEndDate';
    const v = (this.form as unknown as Record<string, string>)[prop];
    return v && v.trim() ? v.trim() : undefined;
  }

  openSubmitted(): void {
    if (!this.submittedClaim) return;
    this.router.navigate(['/tenant/claims', this.submittedClaim.id]);
  }

  resetForm(): void {
    this.submittedClaim = null;
    this.submittedBatchNumber = null;
    this.memberId = null;
    this.dependantId = null;
    this.beneficiaryId = null;
    this.providerId = null;
    this.providerOptIn = false;
    this.schemeId = null;
    this.setActiveLine(null);
    this.form = {
      serviceDate: new Date().toISOString().slice(0, 10),
      currencyCode: this.form.currencyCode,
      diagnosisCodes: '',
      procedureCodes: '',
      notes: '',
      isBatched: false,
      batchNumber: '',
      vehicleRegistration: '',
      incidentLocation: '',
      incidentReportRef: '',
      policeReportRef: '',
      propertyAddress: '',
      deathCertificateRef: '',
      deceasedRelationship: '',
      travelDestination: '',
      travelStartDate: '',
      travelEndDate: '',
      disabilityAssessmentRef: '',
      lifeCertificateRef: '',
      singleClaimedAmount: '',
    };
    this.lines = [this.emptyLine()];
    this.attachments = [];
    this.formError = null;
  }
}
