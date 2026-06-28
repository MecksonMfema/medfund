import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { PoliciesService, Property } from '../../../../core/services/policies.service';
import { TenantService } from '../../../../core/services/tenant.service';
import { ContributionsService } from '../../../../core/services/contributions.service';
import { MembersService } from '../../../../core/services/members.service';
import { GroupsService } from '../../../../core/services/groups.service';
import { EntityPickerComponent } from '../../../../shared/components/entity-picker/entity-picker.component';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';
import { ToastService } from '../../../../shared/components/toast/toast.service';

interface PropertyFormState {
  propertyName: string;
  address: string;
  sumInsured: number | null;
  constructionType: string;
  roofType: string;
  locationRiskBand: string;
  securityFeaturesCount: number | null;
  propertyAgeYears: number | null;
  occupancy: string;
  schemeId: string;
  groupId: string;
  ownerMemberId: string;
  // INDIVIDUAL pricing — three columns travel together (V030 pattern).
  billingOverrideAmount: number | null;
  billingOverrideReason: string;
  billingOverrideEffectiveFrom: string;
}

/**
 * Shared add + edit form for properties. When the route carries an :id
 * the form switches to edit mode (PUT); otherwise it POSTs a new
 * property. The override section renders only when the tenant's
 * pricingModel === 'INDIVIDUAL' — same gate as the Member form
 * (see member-detail.component for the canonical pattern).
 */
@Component({
  selector: 'app-property-form',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent, SelectComponent, EntityPickerComponent],
  templateUrl: './property-form.component.html',
  styleUrl: './property-form.component.scss',
})
export class PropertyFormComponent implements OnInit {
  propertyId: string | null = null;
  property: Property | null = null;
  loading = false;
  saving = false;
  errorMessage: string | null = null;

  form: PropertyFormState = {
    propertyName: '', address: '', sumInsured: null,
    constructionType: '', roofType: '', locationRiskBand: '',
    securityFeaturesCount: 0, propertyAgeYears: null, occupancy: '',
    schemeId: '', groupId: '', ownerMemberId: '',
    billingOverrideAmount: null, billingOverrideReason: '', billingOverrideEffectiveFrom: '',
  };

  schemeLabel: string | null = null;
  schemeSublabel: string | null = null;
  groupLabel: string | null = null;
  ownerLabel: string | null = null;

  readonly constructionTypeOptions: SelectOption[] = [
    { value: '', label: 'Not specified' },
    { value: 'BRICK',    label: 'Brick' },
    { value: 'TIMBER',   label: 'Timber' },
    { value: 'CONCRETE', label: 'Concrete' },
    { value: 'OTHER',    label: 'Other' },
  ];
  readonly locationRiskBandOptions: SelectOption[] = [
    { value: '', label: 'Not specified' },
    { value: 'LOW',    label: 'Low' },
    { value: 'MEDIUM', label: 'Medium' },
    { value: 'HIGH',   label: 'High' },
  ];
  readonly occupancyOptions: SelectOption[] = [
    { value: '', label: 'Not specified' },
    { value: 'OWNER',  label: 'Owner-occupied' },
    { value: 'TENANT', label: 'Tenant-occupied' },
    { value: 'VACANT', label: 'Vacant' },
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

  get isEdit(): boolean { return !!this.propertyId; }
  get individualPricing(): boolean {
    return this.tenantSvc.getTenant()?.pricingModel === 'INDIVIDUAL';
  }

  ngOnInit(): void {
    this.propertyId = this.route.snapshot.paramMap.get('id');
    if (this.propertyId) this.loadProperty();
  }

  private loadProperty(): void {
    this.loading = true;
    this.policies.getProperty(this.propertyId!).subscribe({
      next: (p) => {
        this.property = p;
        this.form = {
          propertyName: p.propertyName,
          address: p.address,
          sumInsured: p.sumInsured,
          constructionType: p.constructionType ?? '',
          roofType: p.roofType ?? '',
          locationRiskBand: p.locationRiskBand ?? '',
          securityFeaturesCount: p.securityFeaturesCount ?? 0,
          propertyAgeYears: p.propertyAgeYears ?? null,
          occupancy: p.occupancy ?? '',
          schemeId: p.schemeId, groupId: p.groupId ?? '', ownerMemberId: p.ownerMemberId ?? '',
          billingOverrideAmount: p.billingOverrideAmount ?? null,
          billingOverrideReason: p.billingOverrideReason ?? '',
          billingOverrideEffectiveFrom: p.billingOverrideEffectiveFrom ?? '',
        };
        this.loading = false;
        this.loadPrefillLabels(p);
      },
      error: (err) => {
        this.loading = false;
        this.errorMessage = err?.error?.detail || 'Failed to load property';
      },
    });
  }

  private loadPrefillLabels(p: Property): void {
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
    if (p.ownerMemberId) {
      this.members.getById(p.ownerMemberId).subscribe({
        next: (m) => { this.ownerLabel = `${m.firstName} ${m.lastName}`; },
        error: () => {},
      });
    }
  }

  submit(): void {
    const missing: string[] = [];
    if (!this.form.propertyName.trim()) missing.push('property name');
    if (!this.form.address.trim())      missing.push('address');
    if (this.form.sumInsured == null)   missing.push('sum insured');
    if (!this.form.schemeId)            missing.push('scheme');
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
      propertyName: this.form.propertyName.trim(),
      address: this.form.address.trim(),
      sumInsured: this.form.sumInsured,
      constructionType: this.form.constructionType || undefined,
      roofType: this.form.roofType.trim() || undefined,
      locationRiskBand: this.form.locationRiskBand || undefined,
      securityFeaturesCount: this.form.securityFeaturesCount ?? 0,
      propertyAgeYears: this.form.propertyAgeYears ?? undefined,
      occupancy: this.form.occupancy || undefined,
      schemeId: this.form.schemeId,
      groupId: this.form.groupId || undefined,
      ownerMemberId: this.form.ownerMemberId || undefined,
    };
    if (this.individualPricing && this.form.billingOverrideAmount != null) {
      payload.billingOverrideAmount = this.form.billingOverrideAmount;
      payload.billingOverrideReason = this.form.billingOverrideReason.trim() || undefined;
      payload.billingOverrideEffectiveFrom = this.form.billingOverrideEffectiveFrom;
    }

    const stream = this.isEdit
      ? this.policies.updateProperty(this.propertyId!, payload)
      : this.policies.createProperty(payload);

    stream.subscribe({
      next: (saved) => {
        this.saving = false;
        this.toast.success(this.isEdit ? 'Property updated' : `Property ${saved.propertyName} registered`);
        this.router.navigate(['/tenant/policies/properties']);
      },
      error: (err) => {
        this.saving = false;
        const msg = err?.error?.detail || err?.error?.title || 'Save failed';
        this.errorMessage = msg;
        this.toast.error(msg);
      },
    });
  }

  cancel(): void { this.router.navigate(['/tenant/policies/properties']); }

  clearOverride(): void {
    if (!this.isEdit) return;
    if (!confirm('Remove this property\'s custom premium? Billing reverts to the scheme default next cycle.')) return;
    this.policies.clearPropertyOverride(this.propertyId!).subscribe({
      next: (saved) => {
        this.property = saved;
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
    this.policies.suspendProperty(this.propertyId!).subscribe({
      next: (saved) => { this.property = saved; this.toast.success('Property suspended'); },
      error: (err) => this.toast.error(err?.error?.detail || 'Suspend failed'),
    });
  }

  terminate(): void {
    if (!this.isEdit) return;
    if (!confirm('Terminate this property\'s policy? It will stop billing next cycle.')) return;
    this.policies.terminateProperty(this.propertyId!).subscribe({
      next: (saved) => { this.property = saved; this.toast.success('Property terminated'); },
      error: (err) => this.toast.error(err?.error?.detail || 'Terminate failed'),
    });
  }
}
