import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { forkJoin } from 'rxjs';
import {
  BillingCatalogueService,
  BenefitType,
  PaymentMethod,
  TransactionType,
  DunningConfig,
  BillingCycleConfig,
  UpsertBenefitTypePayload,
  UpsertPaymentMethodPayload,
  UpsertTransactionTypePayload,
} from '../../../../core/services/billing-catalogue.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';
import { AdminService } from '../../../../core/services/admin.service';
import { TenantService } from '../../../../core/services/tenant.service';

type SubSection = 'benefit-types' | 'payment-methods' | 'transaction-types' | 'dunning' | 'cycle' | 'pricing-mode';
type PricingMode = 'STANDARD' | 'INDIVIDUAL' | 'AI_DRIVEN';

interface BenefitTypeDraft extends UpsertBenefitTypePayload {
  id?: string;
  editing?: boolean;
}

interface PaymentMethodDraft extends UpsertPaymentMethodPayload {
  id?: string;
  editing?: boolean;
}

interface TransactionTypeDraft extends UpsertTransactionTypePayload {
  id?: string;
  editing?: boolean;
}

/**
 * Tenant-admin billing catalogues — embedded as a tab inside Settings. All
 * five sub-sections render inline; the segmented control switches between
 * them without changing the route.
 */
@Component({
  selector: 'app-tenant-billing-tab',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, SkeletonComponent, SelectComponent],
  templateUrl: './billing-tab.component.html',
  styleUrl: './billing-tab.component.scss',
})
export class TenantBillingTabComponent implements OnInit {
  active: SubSection = 'benefit-types';
  loading = false;
  errorMessage: string | null = null;
  successMessage: string | null = null;

  benefitTypes: BenefitType[] = [];
  paymentMethods: PaymentMethod[] = [];
  transactionTypes: TransactionType[] = [];
  dunning: DunningConfig | null = null;
  cycle: BillingCycleConfig | null = null;

  // Draft state for inline edit / new-row forms.
  newBenefit: BenefitTypeDraft = this.emptyBenefit();
  newPayment: PaymentMethodDraft = this.emptyPayment();
  newTransaction: TransactionTypeDraft = this.emptyTransaction();

  benefitDraft: BenefitTypeDraft = this.emptyBenefit();
  paymentDraft: PaymentMethodDraft = this.emptyPayment();
  transactionDraft: TransactionTypeDraft = this.emptyTransaction();

  dunningDraft: DunningConfig = {
    graceDays: 7, suspensionDays: 30, writeOffDays: 90,
    autoSuspend: false, autoWriteOff: false,
  };
  cycleDraft: BillingCycleConfig = {
    frequency: 'MONTHLY', dayOfMonth: 1, autoGenerate: false, commitCooldownHours: 3,
  };

  saving = false;
  pendingId: string | null = null;

  readonly subSections: { id: SubSection; label: string; icon: string }[] = [
    { id: 'benefit-types',     label: 'Benefit Types',     icon: 'list' },
    { id: 'payment-methods',   label: 'Payment Methods',   icon: 'credit-card' },
    { id: 'transaction-types', label: 'Transaction Types', icon: 'activity' },
    { id: 'dunning',           label: 'Dunning Rules',     icon: 'alert-triangle' },
    { id: 'cycle',             label: 'Billing Cycle',     icon: 'calendar' },
    { id: 'pricing-mode',      label: 'Pricing Mode',      icon: 'sliders' },
  ];

  // ── SelectComponent options ─────────────────────────────────────────────
  readonly transactionSignOptions: SelectOption[] = [
    { value: '+', label: '+ (debit)' },
    { value: '-', label: '- (credit)' },
  ];
  readonly cycleFrequencyOptions: SelectOption[] = [
    { value: 'MONTHLY',   label: 'Monthly' },
    { value: 'QUARTERLY', label: 'Quarterly' },
    { value: 'ANNUAL',    label: 'Annual' },
    { value: 'CUSTOM',    label: 'Custom (manual only)' },
  ];
  readonly pricingModeOptions: SelectOption[] = [
    { value: 'STANDARD',   label: 'Standard — scheme/age-group default' },
    { value: 'INDIVIDUAL', label: 'Individual — honour per-member overrides' },
    { value: 'AI_DRIVEN',  label: 'AI-driven — scheme default × risk multiplier' },
  ];
  pricingModeDraft: PricingMode = 'STANDARD';

  constructor(
    private svc: BillingCatalogueService,
    private admin: AdminService,
    private tenantSvc: TenantService,
  ) {}

  ngOnInit(): void {
    this.loadAll();
    // Seed the dropdown from the cached tenant snapshot so the field
    // reflects the saved value when the operator opens the tab. Falls
    // back to STANDARD when the tenant predates the column.
    const t = this.tenantSvc.getTenant();
    this.pricingModeDraft = (t?.pricingModel as PricingMode) || 'STANDARD';
  }

  loadAll(): void {
    this.loading = true;
    this.errorMessage = null;
    forkJoin({
      benefits:     this.svc.listBenefitTypes(false),
      payments:     this.svc.listPaymentMethods(false),
      transactions: this.svc.listTransactionTypes(false),
      dunning:      this.svc.getDunning(),
      cycle:        this.svc.getCycle(),
    }).subscribe({
      next: ({ benefits, payments, transactions, dunning, cycle }) => {
        this.benefitTypes = benefits;
        this.paymentMethods = payments;
        this.transactionTypes = transactions;
        this.dunning = dunning;
        this.dunningDraft = { ...dunning };
        this.cycle = cycle;
        this.cycleDraft = { ...cycle };
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || err?.message || 'Failed to load billing catalogues';
        this.loading = false;
      },
    });
  }

  // ── Benefit types ──────────────────────────────────────────────────────

  startEditBenefit(b: BenefitType): void {
    this.benefitDraft = {
      id: b.id, code: b.code, label: b.label, description: b.description,
      sortOrder: b.sortOrder, isActive: b.isActive, editing: true,
    };
  }

  cancelBenefitEdit(): void {
    this.benefitDraft = this.emptyBenefit();
  }

  saveBenefit(draft: BenefitTypeDraft): void {
    const payload: UpsertBenefitTypePayload = {
      code: draft.code, label: draft.label, description: draft.description,
      sortOrder: draft.sortOrder ?? 0, isActive: draft.isActive ?? true,
    };
    const stream = draft.id
      ? this.svc.updateBenefitType(draft.id, payload)
      : this.svc.createBenefitType(payload);
    this.saving = true;
    stream.subscribe({
      next: () => { this.saving = false; this.flash('Benefit type saved'); this.refreshSection('benefit-types'); this.cancelBenefitEdit(); this.newBenefit = this.emptyBenefit(); },
      error: (err) => { this.saving = false; this.errorMessage = err?.error?.detail || 'Save failed'; },
    });
  }

  deleteBenefit(b: BenefitType): void {
    if (!confirm(`Delete benefit type ${b.code}? Existing scheme benefits keep their snapshot label.`)) return;
    this.pendingId = b.id;
    this.svc.deleteBenefitType(b.id).subscribe({
      next: () => { this.pendingId = null; this.refreshSection('benefit-types'); },
      error: (err) => { this.pendingId = null; this.errorMessage = err?.error?.detail || 'Delete failed'; },
    });
  }

  // ── Payment methods ────────────────────────────────────────────────────

  startEditPayment(p: PaymentMethod): void {
    this.paymentDraft = {
      id: p.id, code: p.code, label: p.label, description: p.description,
      requiresReference: p.requiresReference, isActive: p.isActive, editing: true,
    };
  }

  cancelPaymentEdit(): void { this.paymentDraft = this.emptyPayment(); }

  savePayment(draft: PaymentMethodDraft): void {
    const payload: UpsertPaymentMethodPayload = {
      code: draft.code, label: draft.label, description: draft.description,
      requiresReference: draft.requiresReference ?? false, isActive: draft.isActive ?? true,
    };
    const stream = draft.id
      ? this.svc.updatePaymentMethod(draft.id, payload)
      : this.svc.createPaymentMethod(payload);
    this.saving = true;
    stream.subscribe({
      next: () => { this.saving = false; this.flash('Payment method saved'); this.refreshSection('payment-methods'); this.cancelPaymentEdit(); this.newPayment = this.emptyPayment(); },
      error: (err) => { this.saving = false; this.errorMessage = err?.error?.detail || 'Save failed'; },
    });
  }

  deletePayment(p: PaymentMethod): void {
    if (!confirm(`Delete payment method ${p.code}?`)) return;
    this.pendingId = p.id;
    this.svc.deletePaymentMethod(p.id).subscribe({
      next: () => { this.pendingId = null; this.refreshSection('payment-methods'); },
      error: (err) => { this.pendingId = null; this.errorMessage = err?.error?.detail || 'Delete failed'; },
    });
  }

  // ── Transaction types ──────────────────────────────────────────────────

  startEditTransaction(t: TransactionType): void {
    this.transactionDraft = {
      id: t.id, code: t.code, label: t.label, description: t.description,
      sign: t.sign, requiresApproval: t.requiresApproval, isActive: t.isActive, editing: true,
    };
  }

  cancelTransactionEdit(): void { this.transactionDraft = this.emptyTransaction(); }

  saveTransaction(draft: TransactionTypeDraft): void {
    const payload: UpsertTransactionTypePayload = {
      code: draft.code, label: draft.label, description: draft.description,
      sign: draft.sign, requiresApproval: draft.requiresApproval ?? false, isActive: draft.isActive ?? true,
    };
    const stream = draft.id
      ? this.svc.updateTransactionType(draft.id, payload)
      : this.svc.createTransactionType(payload);
    this.saving = true;
    stream.subscribe({
      next: () => { this.saving = false; this.flash('Transaction type saved'); this.refreshSection('transaction-types'); this.cancelTransactionEdit(); this.newTransaction = this.emptyTransaction(); },
      error: (err) => { this.saving = false; this.errorMessage = err?.error?.detail || 'Save failed'; },
    });
  }

  deleteTransaction(t: TransactionType): void {
    if (!confirm(`Delete transaction type ${t.code}?`)) return;
    this.pendingId = t.id;
    this.svc.deleteTransactionType(t.id).subscribe({
      next: () => { this.pendingId = null; this.refreshSection('transaction-types'); },
      error: (err) => { this.pendingId = null; this.errorMessage = err?.error?.detail || 'Delete failed'; },
    });
  }

  // ── Dunning + cycle (singletons) ──────────────────────────────────────

  saveDunning(): void {
    this.saving = true;
    this.svc.upsertDunning(this.dunningDraft).subscribe({
      next: (saved) => { this.dunning = saved; this.dunningDraft = { ...saved }; this.saving = false; this.flash('Dunning rules saved'); },
      error: (err) => { this.saving = false; this.errorMessage = err?.error?.detail || 'Save failed'; },
    });
  }

  saveCycle(): void {
    this.saving = true;
    this.svc.upsertCycle(this.cycleDraft).subscribe({
      next: (saved) => { this.cycle = saved; this.cycleDraft = { ...saved }; this.saving = false; this.flash('Billing cycle saved'); },
      error: (err) => { this.saving = false; this.errorMessage = err?.error?.detail || 'Save failed'; },
    });
  }

  savePricingMode(): void {
    const t = this.tenantSvc.getTenant();
    if (!t?.id) {
      this.errorMessage = 'No tenant in session — refresh and try again';
      return;
    }
    this.saving = true;
    this.admin.updateTenant(t.id, { pricingModel: this.pricingModeDraft }).subscribe({
      next: () => {
        this.saving = false;
        // Refresh the cached snapshot so the new value is visible to
        // every other surface (member forms, billing wizard) without
        // a logout/login round-trip.
        this.tenantSvc.setTenant({ ...t, pricingModel: this.pricingModeDraft });
        this.flash('Pricing mode saved');
      },
      error: (err) => { this.saving = false; this.errorMessage = err?.error?.detail || 'Save failed'; },
    });
  }

  // ── Helpers ───────────────────────────────────────────────────────────

  private refreshSection(section: SubSection): void {
    if (section === 'benefit-types') {
      this.svc.listBenefitTypes(false).subscribe(rows => this.benefitTypes = rows);
    } else if (section === 'payment-methods') {
      this.svc.listPaymentMethods(false).subscribe(rows => this.paymentMethods = rows);
    } else if (section === 'transaction-types') {
      this.svc.listTransactionTypes(false).subscribe(rows => this.transactionTypes = rows);
    }
  }

  private flash(msg: string): void {
    this.successMessage = msg;
    setTimeout(() => (this.successMessage = null), 3000);
  }

  private emptyBenefit(): BenefitTypeDraft {
    return { code: '', label: '', description: '', sortOrder: 0, isActive: true };
  }
  private emptyPayment(): PaymentMethodDraft {
    return { code: '', label: '', description: '', requiresReference: false, isActive: true };
  }
  private emptyTransaction(): TransactionTypeDraft {
    return { code: '', label: '', description: '', sign: '+', requiresApproval: false, isActive: true };
  }
}
