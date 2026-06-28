import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ContributionsService } from '../../../../core/services/contributions.service';
import { GroupsService } from '../../../../core/services/groups.service';
import { Dependant, Member, MembersService } from '../../../../core/services/members.service';
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
}

const EMPTY_DEPENDANT: DependantForm = {
  firstName: '', lastName: '', dateOfBirth: '', gender: '', relationship: '', nationalId: '',
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
  ) {}

  /** Whether the Custom-premium section + Clear-override button render. */
  get individualPricing(): boolean {
    return this.tenantSvc.getTenant()?.pricingModel === 'INDIVIDUAL';
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
    this.dependantForm = { ...EMPTY_DEPENDANT };
  }

  editDependant(d: Dependant): void {
    this.editingDependantId = d.id;
    this.dependantForm = {
      firstName:   d.firstName ?? '',
      lastName:    d.lastName ?? '',
      dateOfBirth: d.dateOfBirth ?? '',
      gender:      d.gender ?? '',
      relationship: d.relationship ?? '',
      nationalId:  d.nationalId ?? '',
    };
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
    this.dependantSaving = true;
    const base = {
      firstName:    this.dependantForm.firstName.trim(),
      lastName:     this.dependantForm.lastName.trim(),
      dateOfBirth:  this.dependantForm.dateOfBirth || undefined,
      gender:       this.dependantForm.gender.trim(),
      relationship: this.dependantForm.relationship.trim(),
      nationalId:   this.dependantForm.nationalId.trim(),
    };
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

  removeDependant(d: Dependant): void {
    if (!confirm(`Remove dependant ${d.firstName} ${d.lastName}?`)) return;
    this.removingId = d.id;
    this.members.removeDependant(d.id).subscribe({
      next: (saved) => {
        this.dependants = this.dependants.map(x => x.id === saved.id ? saved : x);
        this.removingId = null;
        this.toast.success('Dependant removed');
      },
      error: (err) => {
        this.removingId = null;
        this.toast.error(err?.error?.detail || 'Remove failed');
      },
    });
  }

  back(): void { this.router.navigate(['/tenant/members']); }
}
