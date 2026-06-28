import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { PoliciesService, Vehicle } from '../../../../core/services/policies.service';
import { TenantService } from '../../../../core/services/tenant.service';
import { ContributionsService } from '../../../../core/services/contributions.service';
import { MembersService } from '../../../../core/services/members.service';
import { GroupsService } from '../../../../core/services/groups.service';
import { EntityPickerComponent } from '../../../../shared/components/entity-picker/entity-picker.component';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';
import { ToastService } from '../../../../shared/components/toast/toast.service';

interface VehicleFormState {
  registrationNumber: string;
  make: string;
  model: string;
  year: number | null;
  vehicleValue: number | null;
  bodyType: string;
  usageType: string;
  schemeId: string;
  groupId: string;
  ownerMemberId: string;
  // INDIVIDUAL pricing — three columns travel together (V030 pattern).
  billingOverrideAmount: number | null;
  billingOverrideReason: string;
  billingOverrideEffectiveFrom: string;
}

/**
 * Shared add + edit form for vehicles. When the route carries an :id
 * the form switches to edit mode (PUT); otherwise it POSTs a new
 * vehicle. The override section renders only when the tenant's
 * pricingModel === 'INDIVIDUAL' — same gate as the Member form
 * (see member-detail.component for the canonical pattern).
 */
@Component({
  selector: 'app-vehicle-form',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent, SelectComponent, EntityPickerComponent],
  templateUrl: './vehicle-form.component.html',
  styleUrl: './vehicle-form.component.scss',
})
export class VehicleFormComponent implements OnInit {
  vehicleId: string | null = null;
  vehicle: Vehicle | null = null;
  loading = false;
  saving = false;
  errorMessage: string | null = null;

  form: VehicleFormState = {
    registrationNumber: '', make: '', model: '', year: null, vehicleValue: null,
    bodyType: '', usageType: '',
    schemeId: '', groupId: '', ownerMemberId: '',
    billingOverrideAmount: null, billingOverrideReason: '', billingOverrideEffectiveFrom: '',
  };

  schemeLabel: string | null = null;
  schemeSublabel: string | null = null;
  groupLabel: string | null = null;
  ownerLabel: string | null = null;

  readonly bodyTypeOptions: SelectOption[] = [
    { value: '', label: 'Not specified' },
    { value: 'SEDAN',     label: 'Sedan' },
    { value: 'SUV',       label: 'SUV' },
    { value: 'PICKUP',    label: 'Pickup' },
    { value: 'HATCHBACK', label: 'Hatchback' },
    { value: 'COUPE',     label: 'Coupe' },
    { value: 'TRUCK',     label: 'Truck' },
    { value: 'BUS',       label: 'Bus' },
    { value: 'OTHER',     label: 'Other' },
  ];
  readonly usageTypeOptions: SelectOption[] = [
    { value: '', label: 'Not specified' },
    { value: 'PRIVATE',    label: 'Private' },
    { value: 'COMMERCIAL', label: 'Commercial' },
    { value: 'RIDESHARE',  label: 'Rideshare' },
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

  get isEdit(): boolean { return !!this.vehicleId; }
  get individualPricing(): boolean {
    return this.tenantSvc.getTenant()?.pricingModel === 'INDIVIDUAL';
  }

  ngOnInit(): void {
    this.vehicleId = this.route.snapshot.paramMap.get('id');
    if (this.vehicleId) this.loadVehicle();
  }

  private loadVehicle(): void {
    this.loading = true;
    this.policies.getVehicle(this.vehicleId!).subscribe({
      next: (v) => {
        this.vehicle = v;
        this.form = {
          registrationNumber: v.registrationNumber,
          make: v.make, model: v.model, year: v.year,
          vehicleValue: v.vehicleValue,
          bodyType: v.bodyType ?? '', usageType: v.usageType ?? '',
          schemeId: v.schemeId, groupId: v.groupId ?? '', ownerMemberId: v.ownerMemberId ?? '',
          billingOverrideAmount: v.billingOverrideAmount ?? null,
          billingOverrideReason: v.billingOverrideReason ?? '',
          billingOverrideEffectiveFrom: v.billingOverrideEffectiveFrom ?? '',
        };
        this.loading = false;
        this.loadPrefillLabels(v);
      },
      error: (err) => {
        this.loading = false;
        this.errorMessage = err?.error?.detail || 'Failed to load vehicle';
      },
    });
  }

  private loadPrefillLabels(v: Vehicle): void {
    if (v.schemeId) {
      this.contributions.getSchemeById(v.schemeId).subscribe({
        next: (s) => { this.schemeLabel = s.name; this.schemeSublabel = s.schemeType ?? null; },
        error: () => {},
      });
    }
    if (v.groupId) {
      this.groupsService.findById(v.groupId).subscribe({
        next: (g) => { this.groupLabel = g.name; },
        error: () => {},
      });
    }
    if (v.ownerMemberId) {
      this.members.getById(v.ownerMemberId).subscribe({
        next: (m) => { this.ownerLabel = `${m.firstName} ${m.lastName}`; },
        error: () => {},
      });
    }
  }

  submit(): void {
    const missing: string[] = [];
    if (!this.form.registrationNumber.trim()) missing.push('registration number');
    if (!this.form.make.trim())                missing.push('make');
    if (!this.form.model.trim())               missing.push('model');
    if (!this.form.year)                       missing.push('year');
    if (this.form.vehicleValue == null)        missing.push('vehicle value');
    if (!this.form.schemeId)                   missing.push('scheme');
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
      registrationNumber: this.form.registrationNumber.trim(),
      make: this.form.make.trim(), model: this.form.model.trim(),
      year: this.form.year, vehicleValue: this.form.vehicleValue,
      bodyType: this.form.bodyType || undefined,
      usageType: this.form.usageType || undefined,
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
      ? this.policies.updateVehicle(this.vehicleId!, payload)
      : this.policies.createVehicle(payload);

    stream.subscribe({
      next: (saved) => {
        this.saving = false;
        this.toast.success(this.isEdit ? 'Vehicle updated' : `Vehicle ${saved.registrationNumber} registered`);
        this.router.navigate(['/tenant/policies/vehicles']);
      },
      error: (err) => {
        this.saving = false;
        const msg = err?.error?.detail || err?.error?.title || 'Save failed';
        this.errorMessage = msg;
        this.toast.error(msg);
      },
    });
  }

  cancel(): void { this.router.navigate(['/tenant/policies/vehicles']); }

  clearOverride(): void {
    if (!this.isEdit) return;
    if (!confirm('Remove this vehicle\'s custom premium? Billing reverts to the scheme default next cycle.')) return;
    this.policies.clearVehicleOverride(this.vehicleId!).subscribe({
      next: (saved) => {
        this.vehicle = saved;
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
    this.policies.suspendVehicle(this.vehicleId!).subscribe({
      next: (saved) => { this.vehicle = saved; this.toast.success('Vehicle suspended'); },
      error: (err) => this.toast.error(err?.error?.detail || 'Suspend failed'),
    });
  }

  terminate(): void {
    if (!this.isEdit) return;
    if (!confirm('Terminate this vehicle\'s policy? It will stop billing next cycle.')) return;
    this.policies.terminateVehicle(this.vehicleId!).subscribe({
      next: (saved) => { this.vehicle = saved; this.toast.success('Vehicle terminated'); },
      error: (err) => this.toast.error(err?.error?.detail || 'Terminate failed'),
    });
  }
}
