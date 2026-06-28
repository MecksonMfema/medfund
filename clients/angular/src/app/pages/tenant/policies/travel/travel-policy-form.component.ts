import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { PoliciesService, TravelPolicy } from '../../../../core/services/policies.service';
import { TenantService } from '../../../../core/services/tenant.service';
import { ContributionsService } from '../../../../core/services/contributions.service';
import { MembersService } from '../../../../core/services/members.service';
import { GroupsService } from '../../../../core/services/groups.service';
import { EntityPickerComponent } from '../../../../shared/components/entity-picker/entity-picker.component';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';
import { ToastService } from '../../../../shared/components/toast/toast.service';

interface TravelPolicyFormState {
  policyNumber: string;
  tripStartDate: string;
  tripEndDate: string;
  destinationBand: string;
  coverageLevel: string;
  preExistingDeclared: boolean;
  schemeId: string;
  groupId: string;
  travelerMemberId: string;
  // INDIVIDUAL pricing — three columns travel together (V030 pattern).
  billingOverrideAmount: number | null;
  billingOverrideReason: string;
  billingOverrideEffectiveFrom: string;
}

/**
 * Shared add + edit form for travel policies. When the route carries
 * an :id the form switches to edit mode (PUT); otherwise it POSTs a
 * new policy. The override section renders only when the tenant's
 * pricingModel === 'INDIVIDUAL' — same gate as the Member form
 * (see member-detail.component for the canonical pattern).
 *
 * Travel is PERSON-INSURING — the traveller is required at creation
 * and locked in edit mode (rendered as a read-only chip).
 */
@Component({
  selector: 'app-travel-policy-form',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent, SelectComponent, EntityPickerComponent],
  templateUrl: './travel-policy-form.component.html',
  styleUrl: './travel-policy-form.component.scss',
})
export class TravelPolicyFormComponent implements OnInit {
  policyId: string | null = null;
  policy: TravelPolicy | null = null;
  loading = false;
  saving = false;
  errorMessage: string | null = null;

  form: TravelPolicyFormState = {
    policyNumber: '', tripStartDate: '', tripEndDate: '',
    destinationBand: '', coverageLevel: '', preExistingDeclared: false,
    schemeId: '', groupId: '', travelerMemberId: '',
    billingOverrideAmount: null, billingOverrideReason: '', billingOverrideEffectiveFrom: '',
  };

  schemeLabel: string | null = null;
  schemeSublabel: string | null = null;
  groupLabel: string | null = null;
  travelerLabel: string | null = null;

  readonly destinationBandOptions: SelectOption[] = [
    { value: '', label: 'Not specified' },
    { value: 'DOMESTIC',      label: 'Domestic' },
    { value: 'EUROPE',        label: 'Europe' },
    { value: 'ASIA',          label: 'Asia' },
    { value: 'NORTH_AMERICA', label: 'North America' },
    { value: 'OTHER',         label: 'Other' },
  ];
  readonly coverageLevelOptions: SelectOption[] = [
    { value: '', label: 'Not specified' },
    { value: 'BASIC',         label: 'Basic' },
    { value: 'STANDARD',      label: 'Standard' },
    { value: 'COMPREHENSIVE', label: 'Comprehensive' },
  ];

  constructor(
    private policies: PoliciesService,
    private route: ActivatedRoute,
    private router: Router,
    private toast: ToastService,
    private tenantSvc: TenantService,
    private contributions: ContributionsService,
    private members: MembersService,
    private groupsService: GroupsService,
  ) {}

  get isEdit(): boolean { return !!this.policyId; }
  get individualPricing(): boolean {
    return this.tenantSvc.getTenant()?.pricingModel === 'INDIVIDUAL';
  }

  ngOnInit(): void {
    this.policyId = this.route.snapshot.paramMap.get('id');
    if (this.policyId) this.loadPolicy();
  }

  private loadPolicy(): void {
    this.loading = true;
    this.policies.getTravelPolicy(this.policyId!).subscribe({
      next: (p) => {
        this.policy = p;
        this.form = {
          policyNumber: p.policyNumber,
          tripStartDate: p.tripStartDate,
          tripEndDate: p.tripEndDate,
          destinationBand: p.destinationBand ?? '',
          coverageLevel: p.coverageLevel ?? '',
          preExistingDeclared: !!p.preExistingDeclared,
          schemeId: p.schemeId,
          groupId: p.groupId ?? '',
          travelerMemberId: p.travelerMemberId,
          billingOverrideAmount: p.billingOverrideAmount ?? null,
          billingOverrideReason: p.billingOverrideReason ?? '',
          billingOverrideEffectiveFrom: p.billingOverrideEffectiveFrom ?? '',
        };
        this.loading = false;
        this.loadPrefillLabels(p);
      },
      error: (err) => {
        this.loading = false;
        this.errorMessage = err?.error?.detail || 'Failed to load travel policy';
      },
    });
  }

  private loadPrefillLabels(p: TravelPolicy): void {
    if (p.schemeId) {
      this.contributions.getSchemeById(p.schemeId).subscribe({
        next: (s) => { this.schemeLabel = s.name; this.schemeSublabel = s.schemeType ?? null; },
        error: () => {},
      });
    }
    if (p.groupId) {
      this.groupsService.findById(p.groupId).subscribe({
        next: (g) => { this.groupLabel = g.name; },
        error: () => {},
      });
    }
    if (p.travelerMemberId) {
      this.members.getById(p.travelerMemberId).subscribe({
        next: (m) => { this.travelerLabel = `${m.firstName} ${m.lastName}`; },
        error: () => {},
      });
    }
  }

  submit(): void {
    const missing: string[] = [];
    if (!this.form.policyNumber.trim()) missing.push('policy number');
    if (!this.form.tripStartDate)       missing.push('trip start date');
    if (!this.form.tripEndDate)         missing.push('trip end date');
    if (!this.form.schemeId)            missing.push('scheme');
    if (!this.isEdit && !this.form.travelerMemberId) missing.push('traveller');
    if (missing.length > 0) {
      this.errorMessage = `Required field${missing.length > 1 ? 's' : ''} missing: ${missing.join(', ')}.`;
      this.toast.error(this.errorMessage);
      return;
    }
    if (this.form.tripStartDate && this.form.tripEndDate
        && this.form.tripEndDate < this.form.tripStartDate) {
      this.errorMessage = 'Trip end date must be on or after the trip start date.';
      this.toast.error(this.errorMessage);
      return;
    }
    if (this.individualPricing
        && this.form.billingOverrideAmount != null
        && !this.form.billingOverrideEffectiveFrom) {
      this.errorMessage = 'Custom premium: effective_from is required when an override amount is set.';
      this.toast.error(this.errorMessage);
      return;
    }
    this.errorMessage = null;
    this.saving = true;

    const payload: any = {
      policyNumber: this.form.policyNumber.trim(),
      tripStartDate: this.form.tripStartDate,
      tripEndDate: this.form.tripEndDate,
      destinationBand: this.form.destinationBand || undefined,
      coverageLevel: this.form.coverageLevel || undefined,
      preExistingDeclared: this.form.preExistingDeclared,
      schemeId: this.form.schemeId,
      groupId: this.form.groupId || undefined,
    };
    if (!this.isEdit) {
      payload.travelerMemberId = this.form.travelerMemberId;
    }
    if (this.individualPricing && this.form.billingOverrideAmount != null) {
      payload.billingOverrideAmount = this.form.billingOverrideAmount;
      payload.billingOverrideReason = this.form.billingOverrideReason.trim() || undefined;
      payload.billingOverrideEffectiveFrom = this.form.billingOverrideEffectiveFrom;
    }

    const stream = this.isEdit
      ? this.policies.updateTravelPolicy(this.policyId!, payload)
      : this.policies.createTravelPolicy(payload);

    stream.subscribe({
      next: (saved) => {
        this.saving = false;
        this.toast.success(this.isEdit ? 'Travel policy updated' : `Travel policy ${saved.policyNumber} registered`);
        this.router.navigate(['/tenant/policies/travel']);
      },
      error: (err) => {
        this.saving = false;
        const msg = err?.error?.detail || err?.error?.title || 'Save failed';
        this.errorMessage = msg;
        this.toast.error(msg);
      },
    });
  }

  cancel(): void { this.router.navigate(['/tenant/policies/travel']); }

  clearOverride(): void {
    if (!this.isEdit) return;
    if (!confirm('Remove this policy\'s custom premium? Billing reverts to the scheme default next cycle.')) return;
    this.policies.clearTravelPolicyOverride(this.policyId!).subscribe({
      next: (saved) => {
        this.policy = saved;
        this.form.billingOverrideAmount = null;
        this.form.billingOverrideReason = '';
        this.form.billingOverrideEffectiveFrom = '';
        this.toast.success('Custom premium cleared');
      },
      error: (err) => this.toast.error(err?.error?.detail || 'Clear failed'),
    });
  }

  suspend(): void {
    if (!this.isEdit) return;
    this.policies.suspendTravelPolicy(this.policyId!).subscribe({
      next: (saved) => { this.policy = saved; this.toast.success('Travel policy suspended'); },
      error: (err) => this.toast.error(err?.error?.detail || 'Suspend failed'),
    });
  }

  terminate(): void {
    if (!this.isEdit) return;
    if (!confirm('Terminate this travel policy? It will stop billing next cycle.')) return;
    this.policies.terminateTravelPolicy(this.policyId!).subscribe({
      next: (saved) => { this.policy = saved; this.toast.success('Travel policy terminated'); },
      error: (err) => this.toast.error(err?.error?.detail || 'Terminate failed'),
    });
  }
}
