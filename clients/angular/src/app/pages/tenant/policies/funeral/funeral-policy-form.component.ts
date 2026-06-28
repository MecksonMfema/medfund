import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { FuneralPolicy, PoliciesService } from '../../../../core/services/policies.service';
import { TenantService } from '../../../../core/services/tenant.service';
import { ContributionsService } from '../../../../core/services/contributions.service';
import { MembersService } from '../../../../core/services/members.service';
import { GroupsService } from '../../../../core/services/groups.service';
import { EntityPickerComponent } from '../../../../shared/components/entity-picker/entity-picker.component';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { ToastService } from '../../../../shared/components/toast/toast.service';

interface FuneralFormState {
  policyNumber: string;
  coverAmount: number | null;
  livesCovered: number;
  healthDeclaration: string;
  schemeId: string;
  groupId: string;
  principalMemberId: string;
  // INDIVIDUAL pricing — three columns travel together (V030 pattern).
  billingOverrideAmount: number | null;
  billingOverrideReason: string;
  billingOverrideEffectiveFrom: string;
}

/**
 * Shared add + edit form for funeral policies. When the route carries
 * an :id the form switches to edit mode (PUT); otherwise it POSTs a
 * new policy. The override section renders only when the tenant's
 * pricingModel === 'INDIVIDUAL' — same gate as the Member form
 * (see member-detail.component for the canonical pattern).
 *
 * principalMemberId is REQUIRED and immutable after creation — same
 * rule as Life's insuredMemberId. In edit mode the picker is replaced
 * with a read-only chip and the field is omitted from the PUT payload.
 */
@Component({
  selector: 'app-funeral-policy-form',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent, EntityPickerComponent],
  templateUrl: './funeral-policy-form.component.html',
  styleUrl: './funeral-policy-form.component.scss',
})
export class FuneralPolicyFormComponent implements OnInit {
  policyId: string | null = null;
  policy: FuneralPolicy | null = null;
  loading = false;
  saving = false;
  errorMessage: string | null = null;

  form: FuneralFormState = {
    policyNumber: '', coverAmount: null, livesCovered: 1, healthDeclaration: '',
    schemeId: '', groupId: '', principalMemberId: '',
    billingOverrideAmount: null, billingOverrideReason: '', billingOverrideEffectiveFrom: '',
  };

  schemeLabel: string | null = null;
  schemeSublabel: string | null = null;
  groupLabel: string | null = null;
  principalLabel: string | null = null;

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
    this.policies.getFuneralPolicy(this.policyId!).subscribe({
      next: (p) => {
        this.policy = p;
        this.form = {
          policyNumber: p.policyNumber,
          coverAmount: p.coverAmount,
          livesCovered: p.livesCovered,
          healthDeclaration: p.healthDeclaration ?? '',
          schemeId: p.schemeId,
          groupId: p.groupId ?? '',
          principalMemberId: p.principalMemberId,
          billingOverrideAmount: p.billingOverrideAmount ?? null,
          billingOverrideReason: p.billingOverrideReason ?? '',
          billingOverrideEffectiveFrom: p.billingOverrideEffectiveFrom ?? '',
        };
        this.loading = false;
        this.loadPrefillLabels(p);
      },
      error: (err) => {
        this.loading = false;
        this.errorMessage = err?.error?.detail || 'Failed to load funeral policy';
      },
    });
  }

  private loadPrefillLabels(p: FuneralPolicy): void {
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
    if (p.principalMemberId) {
      this.members.getById(p.principalMemberId).subscribe({
        next: (m) => { this.principalLabel = `${m.firstName} ${m.lastName}`; },
        error: () => {},
      });
    }
  }

  submit(): void {
    const missing: string[] = [];
    if (!this.form.policyNumber.trim())     missing.push('policy number');
    if (this.form.coverAmount == null)      missing.push('cover amount');
    if (this.form.livesCovered == null
        || this.form.livesCovered < 1)      missing.push('lives covered');
    if (!this.form.schemeId)                missing.push('scheme');
    if (!this.isEdit && !this.form.principalMemberId) missing.push('principal member');
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
      coverAmount: this.form.coverAmount,
      livesCovered: this.form.livesCovered,
      healthDeclaration: this.form.healthDeclaration.trim() || undefined,
      schemeId: this.form.schemeId,
      groupId: this.form.groupId || undefined,
    };
    if (!this.isEdit) {
      // principalMemberId is immutable after creation — only send on POST.
      payload.principalMemberId = this.form.principalMemberId;
    }
    if (this.individualPricing && this.form.billingOverrideAmount != null) {
      payload.billingOverrideAmount = this.form.billingOverrideAmount;
      payload.billingOverrideReason = this.form.billingOverrideReason.trim() || undefined;
      payload.billingOverrideEffectiveFrom = this.form.billingOverrideEffectiveFrom;
    }

    const stream = this.isEdit
      ? this.policies.updateFuneralPolicy(this.policyId!, payload)
      : this.policies.createFuneralPolicy(payload);

    stream.subscribe({
      next: (saved) => {
        this.saving = false;
        this.toast.success(this.isEdit ? 'Funeral policy updated' : `Funeral policy ${saved.policyNumber} registered`);
        this.router.navigate(['/tenant/policies/funeral']);
      },
      error: (err) => {
        this.saving = false;
        const msg = err?.error?.detail || err?.error?.title || 'Save failed';
        this.errorMessage = msg;
        this.toast.error(msg);
      },
    });
  }

  cancel(): void { this.router.navigate(['/tenant/policies/funeral']); }

  clearOverride(): void {
    if (!this.isEdit) return;
    if (!confirm('Remove this policy\'s custom premium? Billing reverts to the scheme default next cycle.')) return;
    this.policies.clearFuneralPolicyOverride(this.policyId!).subscribe({
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
    this.policies.suspendFuneralPolicy(this.policyId!).subscribe({
      next: (saved) => { this.policy = saved; this.toast.success('Funeral policy suspended'); },
      error: (err) => this.toast.error(err?.error?.detail || 'Suspend failed'),
    });
  }

  terminate(): void {
    if (!this.isEdit) return;
    if (!confirm('Terminate this funeral policy? It will stop billing next cycle.')) return;
    this.policies.terminateFuneralPolicy(this.policyId!).subscribe({
      next: (saved) => { this.policy = saved; this.toast.success('Funeral policy terminated'); },
      error: (err) => this.toast.error(err?.error?.detail || 'Terminate failed'),
    });
  }
}
