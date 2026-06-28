import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { LifePolicy, PoliciesService } from '../../../../core/services/policies.service';
import { TenantService } from '../../../../core/services/tenant.service';
import { ContributionsService } from '../../../../core/services/contributions.service';
import { MembersService } from '../../../../core/services/members.service';
import { GroupsService } from '../../../../core/services/groups.service';
import { EntityPickerComponent } from '../../../../shared/components/entity-picker/entity-picker.component';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';
import { ToastService } from '../../../../shared/components/toast/toast.service';

interface LifePolicyFormState {
  policyNumber: string;
  sumAssured: number | null;
  occupationHazardClass: string;
  termMonths: number | null;
  schemeId: string;
  groupId: string;
  insuredMemberId: string;
  // INDIVIDUAL pricing — three columns travel together (V030 pattern).
  billingOverrideAmount: number | null;
  billingOverrideReason: string;
  billingOverrideEffectiveFrom: string;
}

/**
 * Shared add + edit form for life policies. When the route carries
 * an :id the form switches to edit mode (PUT); otherwise it POSTs a
 * new policy. The override section renders only when the tenant's
 * pricingModel === 'INDIVIDUAL' — same gate as the Vehicle/Member
 * forms.
 *
 * LIFE is person-insuring: insuredMemberId is REQUIRED at create
 * time and immutable afterwards. The picker is rendered in ADD mode;
 * in EDIT mode we show a read-only chip with the prefetched member
 * name so the operator can see who's insured without being able to
 * accidentally swap them.
 */
@Component({
  selector: 'app-life-policy-form',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent, SelectComponent, EntityPickerComponent],
  templateUrl: './life-policy-form.component.html',
  styleUrl: './life-policy-form.component.scss',
})
export class LifePolicyFormComponent implements OnInit {
  policyId: string | null = null;
  policy: LifePolicy | null = null;
  loading = false;
  saving = false;
  errorMessage: string | null = null;

  form: LifePolicyFormState = {
    policyNumber: '', sumAssured: null, occupationHazardClass: '',
    termMonths: null,
    schemeId: '', groupId: '', insuredMemberId: '',
    billingOverrideAmount: null, billingOverrideReason: '', billingOverrideEffectiveFrom: '',
  };

  schemeLabel: string | null = null;
  schemeSublabel: string | null = null;
  groupLabel: string | null = null;
  insuredLabel: string | null = null;

  readonly hazardClassOptions: SelectOption[] = [
    { value: '', label: 'Not specified' },
    { value: 'SEDENTARY',       label: 'Sedentary' },
    { value: 'MANUAL',          label: 'Manual' },
    { value: 'HAZARDOUS',       label: 'Hazardous' },
    { value: 'VERY_HAZARDOUS',  label: 'Very hazardous' },
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
    this.policies.getLifePolicy(this.policyId!).subscribe({
      next: (p) => {
        this.policy = p;
        this.form = {
          policyNumber: p.policyNumber,
          sumAssured: p.sumAssured,
          occupationHazardClass: p.occupationHazardClass ?? '',
          termMonths: p.termMonths,
          schemeId: p.schemeId,
          groupId: p.groupId ?? '',
          insuredMemberId: p.insuredMemberId,
          billingOverrideAmount: p.billingOverrideAmount ?? null,
          billingOverrideReason: p.billingOverrideReason ?? '',
          billingOverrideEffectiveFrom: p.billingOverrideEffectiveFrom ?? '',
        };
        this.loading = false;
        this.loadPrefillLabels(p);
      },
      error: (err) => {
        this.loading = false;
        this.errorMessage = err?.error?.detail || 'Failed to load life policy';
      },
    });
  }

  private loadPrefillLabels(p: LifePolicy): void {
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
    if (p.insuredMemberId) {
      this.members.getById(p.insuredMemberId).subscribe({
        next: (m) => { this.insuredLabel = `${m.firstName} ${m.lastName}`; },
        error: () => {},
      });
    }
  }

  submit(): void {
    const missing: string[] = [];
    if (!this.form.policyNumber.trim())     missing.push('policy number');
    if (this.form.sumAssured == null)       missing.push('sum assured');
    if (this.form.termMonths == null)       missing.push('term (months)');
    if (!this.form.schemeId)                missing.push('scheme');
    if (!this.isEdit && !this.form.insuredMemberId) missing.push('insured member');
    if (missing.length > 0) {
      this.errorMessage = `Required field${missing.length > 1 ? 's' : ''} missing: ${missing.join(', ')}.`;
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
      sumAssured: this.form.sumAssured,
      occupationHazardClass: this.form.occupationHazardClass || undefined,
      termMonths: this.form.termMonths,
      schemeId: this.form.schemeId,
      groupId: this.form.groupId || undefined,
    };
    // insuredMemberId is immutable post-creation — only send on create.
    if (!this.isEdit) {
      payload.insuredMemberId = this.form.insuredMemberId;
    }
    if (this.individualPricing && this.form.billingOverrideAmount != null) {
      payload.billingOverrideAmount = this.form.billingOverrideAmount;
      payload.billingOverrideReason = this.form.billingOverrideReason.trim() || undefined;
      payload.billingOverrideEffectiveFrom = this.form.billingOverrideEffectiveFrom;
    }

    const stream = this.isEdit
      ? this.policies.updateLifePolicy(this.policyId!, payload)
      : this.policies.createLifePolicy(payload);

    stream.subscribe({
      next: (saved) => {
        this.saving = false;
        this.toast.success(this.isEdit ? 'Life policy updated' : `Life policy ${saved.policyNumber} registered`);
        this.router.navigate(['/tenant/policies/life']);
      },
      error: (err) => {
        this.saving = false;
        const msg = err?.error?.detail || err?.error?.title || 'Save failed';
        this.errorMessage = msg;
        this.toast.error(msg);
      },
    });
  }

  cancel(): void { this.router.navigate(['/tenant/policies/life']); }

  clearOverride(): void {
    if (!this.isEdit) return;
    if (!confirm('Remove this life policy\'s custom premium? Billing reverts to the scheme default next cycle.')) return;
    this.policies.clearLifePolicyOverride(this.policyId!).subscribe({
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
    this.policies.suspendLifePolicy(this.policyId!).subscribe({
      next: (saved) => { this.policy = saved; this.toast.success('Life policy suspended'); },
      error: (err) => this.toast.error(err?.error?.detail || 'Suspend failed'),
    });
  }

  terminate(): void {
    if (!this.isEdit) return;
    if (!confirm('Terminate this life policy? It will stop billing next cycle.')) return;
    this.policies.terminateLifePolicy(this.policyId!).subscribe({
      next: (saved) => { this.policy = saved; this.toast.success('Life policy terminated'); },
      error: (err) => this.toast.error(err?.error?.detail || 'Terminate failed'),
    });
  }
}
