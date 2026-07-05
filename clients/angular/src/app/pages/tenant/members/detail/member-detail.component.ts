import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AgeGroup, ContributionsService } from '../../../../core/services/contributions.service';
import { GroupsService } from '../../../../core/services/groups.service';
import { Dependant, Member, MembersService } from '../../../../core/services/members.service';
import { PricingSuggestionResponse, PricingSuggestionService } from '../../../../core/services/pricing-suggestion.service';
import { EntityPickerComponent } from '../../../../shared/components/entity-picker/entity-picker.component';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';
import { ToastService } from '../../../../shared/components/toast/toast.service';
import { TenantService } from '../../../../core/services/tenant.service';

interface MemberForm {
  firstName: string;
  lastName: string;
  gender: string;
  nationalId: string;
  email: string;
  phone: string;
  address: string;
  groupId: string;
  schemeId: string;
  /**
   * Custom-premium triple (V030). Only rendered + sent when the tenant's
   * pricingModel === 'INDIVIDUAL'. The backend enforces that
   * effectiveFrom is non-null when amount is set; the form mirrors that
   * client-side so we surface a 400 as a friendly inline message.
   */
  billingOverrideAmount: number | null;
  billingOverrideReason: string;
  billingOverrideEffectiveFrom: string;
}

interface DependantForm {
  firstName: string;
  lastName: string;
  dateOfBirth: string;
  gender: string;
  relationship: string;
  nationalId: string;
  /**
   * Effective date the dependant becomes a beneficiary (V047).
   * Always the 1st of a month; the input snaps to day-1 on change.
   * Defaults to the parent member's month on the add flow.
   */
  enrollmentDate: string;
  /**
   * Custom-premium triple. Rendered + sent only when the tenant's
   * pricingModel === 'INDIVIDUAL' or 'AI_DRIVEN'. Same amount +
   * effective_from rule as the member override.
   */
  billingOverrideAmount: number | null;
  billingOverrideReason: string;
  billingOverrideEffectiveFrom: string;
  billingAgeGroupId: string;
  // Risk signals used by the AI suggestion for a dependant.
  smoker: boolean;
  hasChronicConditions: boolean;
  bmi: number | null;
}

const EMPTY_DEPENDANT: DependantForm = {
  firstName: '', lastName: '', dateOfBirth: '', gender: '', relationship: '', nationalId: '',
  enrollmentDate: '',
  billingOverrideAmount: null, billingOverrideReason: '', billingOverrideEffectiveFrom: '',
  billingAgeGroupId: '',
  smoker: false, hasChronicConditions: false, bmi: null,
};

@Component({
  selector: 'app-member-detail',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent, EntityPickerComponent, SelectComponent],
  templateUrl: './member-detail.component.html',
  styleUrl: './member-detail.component.scss',
})
export class MemberDetailComponent implements OnInit {
  memberId = '';
  member: Member | null = null;
  loading = false;
  saving = false;
  errorMessage: string | null = null;

  form: MemberForm = {
    firstName: '', lastName: '', gender: '', nationalId: '',
    email: '', phone: '', address: '', groupId: '', schemeId: '',
    billingOverrideAmount: null, billingOverrideReason: '', billingOverrideEffectiveFrom: '',
  };

  /** Age-band options loaded for the member's current scheme. Consumed
   *  only by the dependant collapsible — dependants can be re-classified
   *  to a different band (e.g. a child with a disability who ages out
   *  of the child band by DoB but should stay on the child rate).
   *  Members themselves don't have an age-band override: their band is
   *  resolved from DoB and any per-member price change goes through the
   *  Custom-premium override instead. */
  ageGroupOptions: SelectOption[] = [];
  ageGroupsLoading = false;

  /** Most recent AI suggestion for the member's custom premium. */
  memberAiSuggestion: PricingSuggestionResponse | null = null;
  memberAiRequesting = false;

  /** Risk signals for the member's AI suggestion. Not persisted — just
   *  drive the suggest call. */
  memberRiskSignals = { smoker: false, hasChronicConditions: false, bmi: null as number | null };

  /** Most recent AI suggestion for the dependant's custom premium. */
  dependantAiSuggestion: PricingSuggestionResponse | null = null;
  dependantAiRequesting = false;

  dependants: Dependant[] = [];
  dependantsLoading = false;

  /** Human-readable labels for the currently-selected group + scheme.
   *  Fetched after the member loads so the EntityPicker chips show the
   *  entity names instead of a blank input on first paint. */
  groupLabel: string | null = null;
  groupSublabel: string | null = null;
  schemeLabel: string | null = null;
  schemeSublabel: string | null = null;

  /** id of the dependant whose row is in edit mode, or '__new__' for the add form. */
  editingDependantId: string | null = null;
  dependantForm: DependantForm = { ...EMPTY_DEPENDANT };
  dependantSaving = false;

  /** Per-row pending flag — gates Remove buttons during the soft-delete call. */
  removingId: string | null = null;

  readonly genderOptions: SelectOption[] = [
    { value: '', label: 'Not specified' },
    { value: 'male', label: 'Male' },
    { value: 'female', label: 'Female' },
    { value: 'other', label: 'Other' },
  ];

  readonly dependantGenderOptions: SelectOption[] = [
    { value: 'male', label: 'Male' },
    { value: 'female', label: 'Female' },
    { value: 'other', label: 'Other' },
  ];

  constructor(
    private members: MembersService,
    private groupsService: GroupsService,
    private contributions: ContributionsService,
    private route: ActivatedRoute,
    private router: Router,
    private toast: ToastService,
    private tenantSvc: TenantService,
    private pricingSuggestion: PricingSuggestionService,
  ) {}

  /** Whether the "Suggest with AI" button renders. Only true for
   *  AI_DRIVEN. INDIVIDUAL enables overrides but not the AI helper. */
  get aiSuggestionsEnabled(): boolean {
    return this.tenantSvc.getTenant()?.pricingModel === 'AI_DRIVEN';
  }

  /** Whether the Custom-premium section + Clear-override button render.
   *  AI_DRIVEN is a hybrid mode: same override semantics as INDIVIDUAL,
   *  plus the AI suggestion helper. STANDARD is the only mode without
   *  overrides. Matches the backend resolver's gate. */
  get individualPricing(): boolean {
    const mode = this.tenantSvc.getTenant()?.pricingModel;
    return mode === 'INDIVIDUAL' || mode === 'AI_DRIVEN';
  }

  ngOnInit(): void {
    this.memberId = this.route.snapshot.paramMap.get('id') ?? '';
    if (!this.memberId) {
      this.errorMessage = 'Member id missing from the URL.';
      return;
    }
    this.loadMember();
    this.loadDependants();
  }

  // ── Member ─────────────────────────────────────────────────────────────

  private loadMember(): void {
    this.loading = true;
    this.members.getById(this.memberId).subscribe({
      next: (m) => {
        this.member = m;
        this.form = {
          firstName:  m.firstName  ?? '',
          lastName:   m.lastName   ?? '',
          gender:     (m as any).gender ?? '',
          nationalId: (m as any).nationalId ?? '',
          email:      m.email ?? '',
          phone:      m.phone ?? '',
          address:    (m as any).address ?? '',
          groupId:    m.groupId ?? '',
          schemeId:   m.schemeId ?? '',
          billingOverrideAmount: m.billingOverrideAmount ?? null,
          billingOverrideReason: m.billingOverrideReason ?? '',
          billingOverrideEffectiveFrom: m.billingOverrideEffectiveFrom ?? '',
        };
        this.loading = false;
        this.loadPrefillLabels(m);
        if (m.schemeId) this.loadAgeGroups(m.schemeId);
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load member';
        this.loading = false;
      },
    });
  }

  /** Resolve the current group / scheme IDs to display names so the
   *  EntityPicker chips paint with a label instead of a blank input. */
  private loadPrefillLabels(m: Member): void {
    if (m.groupId) {
      this.groupsService.findById(m.groupId).subscribe({
        next: (g) => {
          this.groupLabel = g.name;
          this.groupSublabel = g.registrationNumber ?? null;
        },
        error: () => { this.groupLabel = null; this.groupSublabel = null; },
      });
    } else {
      this.groupLabel = null;
      this.groupSublabel = null;
    }
    if (m.schemeId) {
      this.contributions.getSchemeById(m.schemeId).subscribe({
        next: (s) => {
          this.schemeLabel = s.name;
          this.schemeSublabel = s.schemeType ?? null;
        },
        error: () => { this.schemeLabel = null; this.schemeSublabel = null; },
      });
    } else {
      this.schemeLabel = null;
      this.schemeSublabel = null;
    }
  }

  /** Load the scheme's age-bands into the picker. Called on member
   *  load and whenever the operator switches the scheme. */
  private loadAgeGroups(schemeId: string): void {
    this.ageGroupsLoading = true;
    this.contributions.getAgeGroupsByScheme(schemeId).subscribe({
      next: (rows) => {
        this.ageGroupOptions = rows.map((g: AgeGroup) => ({
          value: g.id,
          label: `${g.name} (ages ${g.minAge}–${g.maxAge})`,
        }));
        this.ageGroupsLoading = false;
      },
      error: () => {
        this.ageGroupOptions = [];
        this.ageGroupsLoading = false;
      },
    });
  }

  /** Bound to (valueChange) on the scheme picker. The scheme change
   *  refreshes the dependant collapsible's age-band options — a
   *  different scheme carries different bands, so a stale option list
   *  would let the operator pick a band that doesn't belong to the
   *  new scheme. */
  onSchemeChange(schemeId: string | null): void {
    this.ageGroupOptions = [];
    if (schemeId) this.loadAgeGroups(schemeId);
  }

  /** Ask the AI stub for a suggested premium for the member. Only
   *  wired when tenant.pricingModel === 'AI_DRIVEN'. */
  suggestMemberPremium(): void {
    if (!this.member || !this.form.schemeId || !this.member.dateOfBirth) {
      this.toast.error('Missing scheme or date of birth — the AI needs both to compute a baseline.');
      return;
    }
    this.memberAiRequesting = true;
    this.pricingSuggestion.suggest({
      schemeId: this.form.schemeId,
      dateOfBirth: this.member.dateOfBirth,
      gender: this.form.gender || undefined,
      smoker: this.memberRiskSignals.smoker || undefined,
      hasChronicConditions: this.memberRiskSignals.hasChronicConditions || undefined,
      bmi: this.memberRiskSignals.bmi ?? undefined,
    }).subscribe({
      next: (resp) => {
        this.memberAiSuggestion = resp;
        this.form.billingOverrideAmount = Number(resp.suggestedAmount);
        // Default effective_from to next-cycle's start (1st of next
        // month) if not already set — mirrors real billing anchors.
        if (!this.form.billingOverrideEffectiveFrom) {
          const now = new Date();
          const next = new Date(now.getFullYear(), now.getMonth() + 1, 1);
          this.form.billingOverrideEffectiveFrom = next.toISOString().slice(0, 10);
        }
        this.memberAiRequesting = false;
      },
      error: (err) => {
        this.memberAiRequesting = false;
        this.toast.error(err?.error?.detail || 'Failed to fetch AI suggestion');
      },
    });
  }

  clearMemberAiSuggestion(): void { this.memberAiSuggestion = null; }

  /** Same flow as suggestMemberPremium but for the currently-editing
   *  dependant. Requires the parent member's schemeId + the
   *  dependant's DoB from the collapsible form. */
  suggestDependantPremium(): void {
    if (!this.form.schemeId || !this.dependantForm.dateOfBirth) {
      this.toast.error('Missing scheme or dependant date of birth.');
      return;
    }
    this.dependantAiRequesting = true;
    this.pricingSuggestion.suggest({
      schemeId: this.form.schemeId,
      dateOfBirth: this.dependantForm.dateOfBirth,
      gender: this.dependantForm.gender || undefined,
      smoker: this.dependantForm.smoker || undefined,
      hasChronicConditions: this.dependantForm.hasChronicConditions || undefined,
      bmi: this.dependantForm.bmi ?? undefined,
    }).subscribe({
      next: (resp) => {
        this.dependantAiSuggestion = resp;
        this.dependantForm.billingOverrideAmount = Number(resp.suggestedAmount);
        if (!this.dependantForm.billingOverrideEffectiveFrom) {
          const now = new Date();
          const next = new Date(now.getFullYear(), now.getMonth() + 1, 1);
          this.dependantForm.billingOverrideEffectiveFrom = next.toISOString().slice(0, 10);
        }
        this.dependantAiRequesting = false;
      },
      error: (err) => {
        this.dependantAiRequesting = false;
        this.toast.error(err?.error?.detail || 'Failed to fetch AI suggestion');
      },
    });
  }

  clearDependantAiSuggestion(): void { this.dependantAiSuggestion = null; }

  save(): void {
    this.saving = true;
    this.errorMessage = null;
    // INDIVIDUAL pricing: amount + effective_from must travel together
    // (DB CHECK + service-level guard). Surface the gap inline so we
    // don't round-trip a 400.
    if (this.individualPricing
        && this.form.billingOverrideAmount != null
        && !this.form.billingOverrideEffectiveFrom) {
      this.saving = false;
      this.errorMessage = 'Custom premium: effective_from is required when an override amount is set.';
      this.toast.error(this.errorMessage);
      return;
    }
    const payload: any = {
      firstName:  this.form.firstName.trim()  || undefined,
      lastName:   this.form.lastName.trim()   || undefined,
      gender:     this.form.gender.trim()     || undefined,
      nationalId: this.form.nationalId.trim() || undefined,
      email:      this.form.email.trim()      || undefined,
      phone:      this.form.phone.trim()      || undefined,
      address:    this.form.address.trim()    || undefined,
      groupId:    this.form.groupId           || undefined,
      schemeId:   this.form.schemeId          || undefined,
    };
    if (this.individualPricing && this.form.billingOverrideAmount != null) {
      payload.billingOverrideAmount = this.form.billingOverrideAmount;
      payload.billingOverrideReason = this.form.billingOverrideReason.trim() || undefined;
      payload.billingOverrideEffectiveFrom = this.form.billingOverrideEffectiveFrom;
    }
    this.members.update(this.memberId, payload).subscribe({
      next: (updated) => {
        this.member = updated;
        this.saving = false;
        this.toast.success('Member updated');
      },
      error: (err) => {
        const msg = err?.error?.detail || err?.error?.title || 'Save failed';
        this.errorMessage = msg;
        this.toast.error(msg);
        this.saving = false;
      },
    });
  }

  // Member status transitions — render only the buttons that are legal for
  // the current status. The backend handles the actual rules; the UI gates
  // are cosmetic.
  canActivate():  boolean { return this.member?.status === 'enrolled' || this.member?.status === 'suspended'; }
  canSuspend():   boolean { return this.member?.status === 'active'; }
  canTerminate(): boolean { return this.member?.status !== 'terminated'; }
  canClearOverride(): boolean { return this.member?.billingOverrideAmount != null; }

  activate():  void { this.applyStatusAction('activate',  this.members.activate(this.memberId)); }
  suspend():   void { this.applyStatusAction('suspend',   this.members.suspend(this.memberId)); }
  terminate(): void { this.applyStatusAction('terminate', this.members.terminate(this.memberId)); }

  clearBillingOverride(): void {
    if (!confirm('Remove this member\'s custom premium? Billing will revert to the age-group price next cycle.')) return;
    this.members.clearBillingOverride(this.memberId).subscribe({
      next: (updated) => {
        this.member = updated;
        this.form.billingOverrideAmount = null;
        this.form.billingOverrideReason = '';
        this.form.billingOverrideEffectiveFrom = '';
        this.toast.success('Custom premium cleared');
      },
      error: (err) => this.toast.error(err?.error?.detail || 'Clear failed'),
    });
  }

  private applyStatusAction(label: string, stream: ReturnType<MembersService['activate']>): void {
    stream.subscribe({
      next: (updated) => {
        this.member = updated;
        this.toast.success(`Member ${label}d`);
      },
      error: (err) => this.toast.error(err?.error?.detail || `${label} failed`),
    });
  }

  // ── Dependants ──────────────────────────────────────────────────────────

  private loadDependants(): void {
    this.dependantsLoading = true;
    this.members.getDependants(this.memberId).subscribe({
      next: (list) => { this.dependants = list; this.dependantsLoading = false; },
      error: (err) => {
        this.toast.error(err?.error?.detail || 'Failed to load dependants');
        this.dependantsLoading = false;
      },
    });
  }

  startAddDependant(): void {
    this.editingDependantId = '__new__';
    this.dependantForm = {
      ...EMPTY_DEPENDANT,
      // Default the effective-date to the 1st of the current month so
      // billing starts this cycle. Operator can back-date to trigger
      // arrears on the contributions side, same convention as members.
      enrollmentDate: this.firstOfMonth(new Date().toISOString().slice(0, 10)),
    };
  }

  editDependant(d: Dependant): void {
    this.editingDependantId = d.id;
    this.dependantAiSuggestion = null;
    this.dependantForm = {
      firstName:   d.firstName ?? '',
      lastName:    d.lastName ?? '',
      dateOfBirth: d.dateOfBirth ?? '',
      gender:      d.gender ?? '',
      relationship: d.relationship ?? '',
      nationalId:  d.nationalId ?? '',
      enrollmentDate: d.enrollmentDate ?? '',
      billingOverrideAmount:        (d as any).billingOverrideAmount ?? null,
      billingOverrideReason:        (d as any).billingOverrideReason ?? '',
      billingOverrideEffectiveFrom: (d as any).billingOverrideEffectiveFrom ?? '',
      billingAgeGroupId:            (d as any).billingAgeGroupId ?? '',
      // Risk signals aren't persisted on the dependant; reset each
      // time the operator opens the edit collapsible.
      smoker: false, hasChronicConditions: false, bmi: null,
    };
  }

  /** Normalise an ISO YYYY-MM-DD date to the 1st of its month. Same
   *  helper the member enrol form uses; kept private + inlined here to
   *  avoid a shared-utility hop for one date. */
  private firstOfMonth(iso: string): string {
    if (!iso || iso.length < 7) return iso;
    return iso.slice(0, 8) + '01';
  }

  /** Bound to (ngModelChange) on the dependant enrolment-date input so
   *  any operator pick snaps to the 1st of the chosen month. */
  onDependantEnrollmentDateChange(): void {
    this.dependantForm.enrollmentDate = this.firstOfMonth(this.dependantForm.enrollmentDate);
  }

  cancelDependantEdit(): void {
    this.editingDependantId = null;
    this.dependantForm = { ...EMPTY_DEPENDANT };
  }

  saveDependant(): void {
    if (!this.editingDependantId) return;
    const isNew = this.editingDependantId === '__new__';
    // Add-flow requires the full required set. Edits are partial — null
    // fields just mean "no change" — but if the operator clears gender or
    // national ID on an edit row we still reject to keep the row valid.
    const missing: string[] = [];
    if (!this.dependantForm.firstName.trim())   missing.push('first name');
    if (!this.dependantForm.lastName.trim())    missing.push('last name');
    if (!this.dependantForm.relationship.trim()) missing.push('relationship');
    if (!this.dependantForm.gender.trim())       missing.push('gender');
    if (!this.dependantForm.nationalId.trim())   missing.push('national ID');
    if (isNew && !this.dependantForm.dateOfBirth) missing.push('date of birth');
    if (missing.length > 0) {
      this.toast.error(`Required field${missing.length > 1 ? 's' : ''} missing: ${missing.join(', ')}.`);
      return;
    }
    // Custom-premium consistency: amount without effective_from is
    // rejected by the backend's early guard. Mirror it client-side
    // for a friendly toast rather than a 400 round-trip.
    if (this.individualPricing
        && this.dependantForm.billingOverrideAmount != null
        && !this.dependantForm.billingOverrideEffectiveFrom) {
      this.toast.error('Custom premium: effective_from is required when an override amount is set.');
      return;
    }
    this.dependantSaving = true;
    const base: any = {
      firstName:    this.dependantForm.firstName.trim(),
      lastName:     this.dependantForm.lastName.trim(),
      dateOfBirth:  this.dependantForm.dateOfBirth || undefined,
      gender:       this.dependantForm.gender.trim(),
      relationship: this.dependantForm.relationship.trim(),
      nationalId:   this.dependantForm.nationalId.trim(),
    };
    // Always the 1st of the chosen month (snapped by the input handler
    // and defended here in case a stale draft made it through). Absent
    // → let the backend default to today's 1st.
    if (this.dependantForm.enrollmentDate) {
      base.enrollmentDate = this.firstOfMonth(this.dependantForm.enrollmentDate);
    }
    if (this.individualPricing && this.dependantForm.billingOverrideAmount != null) {
      base.billingOverrideAmount = this.dependantForm.billingOverrideAmount;
      base.billingOverrideReason = this.dependantForm.billingOverrideReason.trim() || undefined;
      base.billingOverrideEffectiveFrom = this.dependantForm.billingOverrideEffectiveFrom;
    }
    // Manual age-band override — applies for every pricing model.
    if (this.dependantForm.billingAgeGroupId) {
      base.billingAgeGroupId = this.dependantForm.billingAgeGroupId;
    }
    const stream = isNew
      ? this.members.addDependant({ memberId: this.memberId, ...base })
      : this.members.updateDependant(this.editingDependantId!, base);
    stream.subscribe({
      next: (saved) => {
        if (isNew) {
          this.dependants = [...this.dependants, saved];
        } else {
          this.dependants = this.dependants.map(d => d.id === saved.id ? saved : d);
        }
        this.cancelDependantEdit();
        this.dependantSaving = false;
        this.toast.success(isNew ? 'Dependant added' : 'Dependant updated');
      },
      error: (err) => {
        this.dependantSaving = false;
        this.toast.error(err?.error?.detail || err?.error?.title || 'Save failed');
      },
    });
  }

  /**
   * Deactivate a dependant. V046: dependants are never deleted, only
   * deactivated with an effective date. Billing continues up to and
   * including the cycle that contains the effective date. Operator
   * picks the date via a lightweight prompt seeded with today (Cancel
   * = abort, empty answer = today, valid ISO = that date).
   */
  deactivateDependant(d: Dependant): void {
    const today = new Date().toISOString().slice(0, 10);
    const answer = prompt(
      `Terminate dependant ${d.firstName} ${d.lastName}.\n\n` +
      `Effective date (YYYY-MM-DD). Billing continues up to and including that month; empty = today.`,
      today,
    );
    if (answer === null) return; // Cancel
    const effectiveDate = answer.trim() || today;
    // Guard against a garbled ISO string — the backend would 400 but a
    // friendly toast is nicer than a round-trip.
    if (!/^\d{4}-\d{2}-\d{2}$/.test(effectiveDate)) {
      this.toast.error(`Invalid date "${effectiveDate}". Use YYYY-MM-DD.`);
      return;
    }
    this.removingId = d.id;
    this.members.deactivateDependant(d.id, effectiveDate).subscribe({
      next: (saved) => {
        this.dependants = this.dependants.map(x => x.id === saved.id ? saved : x);
        this.removingId = null;
        this.toast.success(`Dependant terminated effective ${effectiveDate}`);
      },
      error: (err) => {
        this.removingId = null;
        this.toast.error(err?.error?.detail || 'Termination failed');
      },
    });
  }

  back(): void { this.router.navigate(['/tenant/members']); }
}
