import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { MembersService } from '../../../../core/services/members.service';
import { EntityPickerComponent } from '../../../../shared/components/entity-picker/entity-picker.component';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';
import { ToastService } from '../../../../shared/components/toast/toast.service';
import { TenantService } from '../../../../core/services/tenant.service';
import { PricingSuggestionResponse, PricingSuggestionService } from '../../../../core/services/pricing-suggestion.service';
import { ContributionsService, Scheme } from '../../../../core/services/contributions.service';

interface AddMemberForm {
  firstName: string;
  lastName: string;
  dateOfBirth: string;
  gender: string;
  nationalId: string;
  email: string;
  phone: string;
  address: string;
  groupId: string;
  schemeId: string;
  enrollmentDate: string;
  /**
   * Custom-premium triple (V030). Rendered + sent only when the tenant's
   * pricingModel === 'INDIVIDUAL' or 'AI_DRIVEN' (both honour overrides).
   * The backend enforces that effectiveFrom is required whenever amount
   * is set — mirrored client-side so we surface the constraint inline.
   */
  billingOverrideAmount: number | null;
  billingOverrideReason: string;
  billingOverrideEffectiveFrom: string;
  // ── AI risk signals (only sent to the suggestion endpoint) ──────
  smoker: boolean;
  hasChronicConditions: boolean;
  bmi: number | null;
}

@Component({
  selector: 'app-member-form',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent, EntityPickerComponent, SelectComponent],
  templateUrl: './member-form.component.html',
  styleUrl: './member-form.component.scss',
})
export class MemberFormComponent {
  saving = false;
  errorMessage: string | null = null;

  readonly genderOptions: SelectOption[] = [
    { value: 'male', label: 'Male' },
    { value: 'female', label: 'Female' },
    { value: 'other', label: 'Other' },
  ];

  form: AddMemberForm = {
    firstName: '', lastName: '', dateOfBirth: '',
    gender: '', nationalId: '', email: '', phone: '', address: '',
    groupId: '', schemeId: '',
    // Default to the 1st of the current month — enrollment dates are
    // always month-anchored.
    enrollmentDate: new Date().toISOString().slice(0, 8) + '01',
    billingOverrideAmount: null,
    billingOverrideReason: '',
    billingOverrideEffectiveFrom: '',
    smoker: false,
    hasChronicConditions: false,
    bmi: null,
  };

  /** Latest AI suggestion. Held on the component so the rationale +
   *  factors list stay visible after the operator clicks "Suggest"
   *  and until they clear or re-request. */
  aiSuggestion: PricingSuggestionResponse | null = null;
  aiRequesting = false;

  /** Loaded whenever the operator picks a scheme so the age-range
   *  warning banner (V050) can render. Null until first pick. */
  selectedScheme: Scheme | null = null;

  constructor(
    private members: MembersService,
    private router: Router,
    private toast: ToastService,
    private tenantSvc: TenantService,
    private pricingSuggestion: PricingSuggestionService,
    private contributions: ContributionsService,
  ) {}

  /** Load the scheme's age-range when the entity-picker selects one so
   *  the enrolment form can warn ahead of the backend 422. */
  onSchemeChange(schemeId: string | null): void {
    if (!schemeId) { this.selectedScheme = null; return; }
    this.contributions.getSchemeById(schemeId).subscribe({
      next: (s) => { this.selectedScheme = s; },
      error: () => { this.selectedScheme = null; },
    });
  }

  /**
   * Computed age at enrolment if DoB is set, else null. Used by the age
   * banner + the AI tenure feature (which sends 0 for a fresh joiner).
   */
  get ageAtEnrolment(): number | null {
    if (!this.form.dateOfBirth || !this.form.enrollmentDate) return null;
    const dob = new Date(this.form.dateOfBirth);
    const enrol = new Date(this.form.enrollmentDate);
    if (Number.isNaN(dob.getTime()) || Number.isNaN(enrol.getTime())) return null;
    let age = enrol.getFullYear() - dob.getFullYear();
    const m = enrol.getMonth() - dob.getMonth();
    if (m < 0 || (m === 0 && enrol.getDate() < dob.getDate())) age -= 1;
    return age;
  }

  /** Non-null message when DoB implies an age outside the picked scheme's
   *  min_age / max_age gate; null when fine. Backend is still authoritative;
   *  this just surfaces the constraint pre-submit. */
  get ageOutOfRangeWarning(): string | null {
    const s = this.selectedScheme;
    const age = this.ageAtEnrolment;
    if (!s || age === null) return null;
    if (s.minAge != null && age < s.minAge) {
      return `Member is ${age} at enrolment; scheme "${s.name}" requires at least ${s.minAge}. Backend will reject.`;
    }
    if (s.maxAge != null && age > s.maxAge) {
      return `Member is ${age} at enrolment; scheme "${s.name}" caps at ${s.maxAge}. Backend will reject.`;
    }
    return null;
  }

  /** Whether the Custom-premium section renders. AI_DRIVEN is a
   *  hybrid mode: same per-member override semantics as INDIVIDUAL,
   *  plus an AI-suggestion helper at enrolment. STANDARD is the only
   *  mode without overrides. Matches the backend resolver's gate. */
  get individualPricing(): boolean {
    const mode = this.tenantSvc.getTenant()?.pricingModel;
    return mode === 'INDIVIDUAL' || mode === 'AI_DRIVEN';
  }

  /** Whether the "Suggest with AI" button renders. Only true for
   *  AI_DRIVEN — an INDIVIDUAL tenant enabled overrides but hasn't
   *  opted into the AI-assist workflow. */
  get aiSuggestionsEnabled(): boolean {
    return this.tenantSvc.getTenant()?.pricingModel === 'AI_DRIVEN';
  }

  /** The tenant's current pricing model, surfaced in the discoverability
   *  hint on STANDARD tenants so the operator sees exactly which
   *  mode is in force. Defaults to 'STANDARD' to match the backend
   *  fallback when the column is null. */
  get tenantPricingModel(): string {
    return this.tenantSvc.getTenant()?.pricingModel ?? 'STANDARD';
  }

  /** Normalise an ISO YYYY-MM-DD date to the 1st of its month. */
  private firstOfMonth(iso: string): string {
    if (!iso || iso.length < 7) return iso;
    return iso.slice(0, 8) + '01';
  }

  /** Bound to (ngModelChange) on the enrollment-date input so any operator
   *  pick snaps to the 1st of the chosen month before it sits in form state. */
  onEnrollmentDateChange(): void {
    this.form.enrollmentDate = this.firstOfMonth(this.form.enrollmentDate);
  }

  /** Ask the AI stub for a suggested premium and pre-fill the amount
   *  field. Only wired when tenant.pricingModel === 'AI_DRIVEN'.
   *  Requires the scheme + DoB (the two backend-required inputs);
   *  everything else is optional risk-signal enrichment. */
  suggestPremium(): void {
    if (!this.form.schemeId) {
      this.toast.error('Pick a scheme first — the AI needs it to compute a baseline.');
      return;
    }
    if (!this.form.dateOfBirth) {
      this.toast.error('Enter a date of birth — the AI uses it to pick the age-band.');
      return;
    }
    this.aiRequesting = true;
    this.pricingSuggestion.suggest({
      schemeId: this.form.schemeId,
      dateOfBirth: this.form.dateOfBirth,
      gender: this.form.gender || undefined,
      smoker: this.form.smoker || undefined,
      hasChronicConditions: this.form.hasChronicConditions || undefined,
      bmi: this.form.bmi ?? undefined,
      // V050 Layer 5: fresh joiners have 0 tenure; the AI treats it as a
      // neutral feature. The member-detail form (edit) computes real
      // tenure from `today − enrollmentDate`.
      tenureYears: 0,
    }).subscribe({
      next: (resp) => {
        this.aiSuggestion = resp;
        // Pre-fill the amount so the operator can accept the number
        // with one keystroke (Save). They can still edit before save.
        this.form.billingOverrideAmount = Number(resp.suggestedAmount);
        // Default the effective-from to the enrolment date so the
        // half-filled-triple validation doesn't fire on a fresh
        // suggestion. Operator can pull it back if they want.
        if (!this.form.billingOverrideEffectiveFrom) {
          this.form.billingOverrideEffectiveFrom = this.form.enrollmentDate;
        }
        this.aiRequesting = false;
      },
      error: (err) => {
        this.aiRequesting = false;
        this.toast.error(err?.error?.detail || 'Failed to fetch AI suggestion');
      },
    });
  }

  /** Discard the current suggestion without touching the amount so the
   *  operator can keep their manually-entered override. */
  clearAiSuggestion(): void {
    this.aiSuggestion = null;
  }

  submit(): void {
    const missing: string[] = [];
    if (!this.form.firstName.trim())  missing.push('first name');
    if (!this.form.lastName.trim())   missing.push('last name');
    if (!this.form.dateOfBirth)       missing.push('date of birth');
    if (!this.form.gender.trim())     missing.push('gender');
    if (!this.form.nationalId.trim()) missing.push('national ID');
    if (!this.form.email.trim())      missing.push('email');
    if (!this.form.schemeId)          missing.push('scheme');
    if (missing.length > 0) {
      this.errorMessage = `Required field${missing.length > 1 ? 's' : ''} missing: ${missing.join(', ')}.`;
      return;
    }
    // Custom-premium consistency: amount without an effective_from would
    // trip the DB CHECK. Guard inline for a friendly message rather
    // than a 400 round-trip. Only relevant when the tenant is on
    // INDIVIDUAL pricing (STANDARD tenants ignore per-member overrides).
    if (this.individualPricing
        && this.form.billingOverrideAmount != null
        && !this.form.billingOverrideEffectiveFrom) {
      this.errorMessage = 'Custom premium: effective_from is required when an override amount is set.';
      this.toast.error(this.errorMessage);
      return;
    }
    this.saving = true;
    this.errorMessage = null;
    const payload: any = {
      firstName:      this.form.firstName.trim(),
      lastName:       this.form.lastName.trim(),
      dateOfBirth:    this.form.dateOfBirth,
      gender:         this.form.gender.trim(),
      nationalId:     this.form.nationalId.trim(),
      email:          this.form.email.trim(),
      phone:          this.form.phone.trim()      || undefined,
      address:        this.form.address.trim()    || undefined,
      groupId:        this.form.groupId           || undefined,
      schemeId:       this.form.schemeId,
      // Always the 1st of the chosen month. Back-dating is allowed — the
      // contributions side will post the arrears adjustment when the
      // enrolment date is in a past period (deferred to a follow-up).
      enrollmentDate: this.firstOfMonth(this.form.enrollmentDate),
    };
    if (this.individualPricing && this.form.billingOverrideAmount != null) {
      payload.billingOverrideAmount = this.form.billingOverrideAmount;
      payload.billingOverrideReason = this.form.billingOverrideReason.trim() || undefined;
      payload.billingOverrideEffectiveFrom = this.form.billingOverrideEffectiveFrom;
    }
    this.members.enroll(payload).subscribe({
      next: (saved) => {
        this.saving = false;
        this.toast.success(`Member ${saved.firstName} ${saved.lastName} enrolled`);
        this.router.navigate(['/tenant/members', saved.id]);
      },
      error: (err) => {
        this.saving = false;
        const msg = err?.error?.detail || err?.error?.title || 'Enrolment failed';
        this.errorMessage = msg;
        this.toast.error(msg);
      },
    });
  }
}
