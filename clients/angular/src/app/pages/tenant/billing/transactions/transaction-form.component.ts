import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { Subject, debounceTime, distinctUntilChanged, forkJoin, switchMap, of } from 'rxjs';
import {
  Contribution,
  ContributionsService,
  RecordTransactionPayload,
} from '../../../../core/services/contributions.service';
import {
  BillingCatalogueService,
  PaymentMethod,
  TransactionType,
} from '../../../../core/services/billing-catalogue.service';
import { CurrencyService, TenantCurrencyConfig } from '../../../../core/services/currency.service';
import { TenantService } from '../../../../core/services/tenant.service';
import { GroupsService, Group } from '../../../../core/services/groups.service';
import { MembersService, Member } from '../../../../core/services/members.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';
import { HumanizePipe } from '../../../../shared/pipes/humanize.pipe';

type TargetType = 'GROUP' | 'INDIVIDUAL';

interface TargetOption {
  id: string;
  label: string;
  sublabel?: string;
}

@Component({
  selector: 'app-transaction-form',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent, SelectComponent, HumanizePipe],
  templateUrl: './transaction-form.component.html',
  styleUrl: './transaction-form.component.scss',
})
export class TransactionFormComponent implements OnInit {
  paymentMethods: PaymentMethod[] = [];
  transactionTypes: TransactionType[] = [];
  currencies: TenantCurrencyConfig[] = [];

  saving = false;
  errorMessage: string | null = null;
  successMessage: string | null = null;

  // ── Target picker state ────────────────────────────────────────────────
  membershipModel: 'INDIVIDUAL_ONLY' | 'GROUP_ONLY' | 'BOTH' = 'BOTH';
  targetType: TargetType = 'GROUP';
  targetQuery = '';
  targetMatches: TargetOption[] = [];
  targetSearching = false;
  selectedTarget: TargetOption | null = null;
  showMatches = false;

  // ── Contribution picker (depends on selected target) ───────────────────
  contributions: Contribution[] = [];
  loadingContributions = false;

  form = {
    contributionId: '',
    amount: '',
    currencyCode: '',
    transactionTypeCode: '',
    paymentMethodCode: '',
    reference: '',
  };

  private query$ = new Subject<string>();
  private humanize = new HumanizePipe();

  get contributionOptions(): SelectOption[] {
    return this.contributions.map(c => ({
      value: c.id,
      label: `${c.periodStart} → ${c.periodEnd} · ${c.amount} ${c.currencyCode} · ${this.humanize.transform(c.status)}`,
    }));
  }

  get currencyOptions(): SelectOption[] {
    return this.currencies.map(c => ({ value: c.currencyCode, label: c.currencyCode }));
  }

  get transactionTypeOptions(): SelectOption[] {
    return this.transactionTypes.map(t => ({ value: t.code, label: `${t.label} (${t.sign})` }));
  }

  get paymentMethodOptions(): SelectOption[] {
    return this.paymentMethods.map(p => ({
      value: p.code,
      label: `${p.label}${p.requiresReference ? ' (requires reference)' : ''}`,
    }));
  }

  constructor(
    private catalogue: BillingCatalogueService,
    private currencyService: CurrencyService,
    private contributionsService: ContributionsService,
    private tenantService: TenantService,
    private groupsService: GroupsService,
    private membersService: MembersService,
    private router: Router,
  ) {}

  ngOnInit(): void {
    const tenant = this.tenantService.getTenant();
    if (!tenant) {
      this.errorMessage = 'No active tenant context';
      return;
    }
    this.membershipModel = tenant.membershipModel ?? 'BOTH';
    // Force the target type when tenant only supports one model.
    if (this.membershipModel === 'INDIVIDUAL_ONLY') this.targetType = 'INDIVIDUAL';
    else if (this.membershipModel === 'GROUP_ONLY') this.targetType = 'GROUP';

    forkJoin({
      payments:     this.catalogue.listPaymentMethods(true),
      transactions: this.catalogue.listTransactionTypes(true),
      currencies:   this.currencyService.listForTenant(tenant.id),
    }).subscribe({
      next: ({ payments, transactions, currencies }) => {
        this.paymentMethods = payments;
        this.transactionTypes = transactions;
        this.currencies = currencies.filter(c => c.isActive && c.isPaymentCurrency);
        const def = this.currencies.find(c => c.isDefault);
        if (!this.form.currencyCode && def) this.form.currencyCode = def.currencyCode;
      },
      error: (err) => { this.errorMessage = err?.error?.detail || 'Failed to load form data'; },
    });

    this.query$
      .pipe(
        debounceTime(350),
        distinctUntilChanged(),
        switchMap((q) => {
          const trimmed = q.trim();
          if (!trimmed) {
            this.targetSearching = false;
            return of<TargetOption[]>([]);
          }
          this.targetSearching = true;
          return this.targetType === 'GROUP'
            ? this.groupsService.search(trimmed).pipe(
                switchMap(rows => of<TargetOption[]>(
                  rows.map((g: Group) => ({
                    id: g.id,
                    label: g.name,
                    sublabel: g.registrationNumber || undefined,
                  }))
                ))
              )
            : this.membersService.searchByName(trimmed).pipe(
                switchMap(rows => of<TargetOption[]>(
                  rows.map((m: Member) => ({
                    id: m.id,
                    label: `${m.firstName} ${m.lastName}`.trim(),
                    sublabel: m.memberNumber,
                  }))
                ))
              );
        }),
      )
      .subscribe({
        next: (matches) => { this.targetMatches = matches; this.targetSearching = false; },
        error: () => { this.targetMatches = []; this.targetSearching = false; },
      });
  }

  // ── Target picker handlers ─────────────────────────────────────────────

  onTargetTypeChange(): void {
    this.clearTarget();
    this.targetQuery = '';
    this.targetMatches = [];
  }

  onTargetQueryChange(): void {
    this.showMatches = true;
    this.query$.next(this.targetQuery);
  }

  pickTarget(t: TargetOption): void {
    this.selectedTarget = t;
    this.targetQuery = t.label;
    this.showMatches = false;
    this.targetMatches = [];
    this.loadContributionsForTarget();
  }

  clearTarget(): void {
    this.selectedTarget = null;
    this.contributions = [];
    this.form.contributionId = '';
    this.targetQuery = '';
  }

  private loadContributionsForTarget(): void {
    if (!this.selectedTarget) return;
    this.loadingContributions = true;
    const stream = this.targetType === 'GROUP'
      ? this.contributionsService.getContributionsByGroup(this.selectedTarget.id)
      : this.contributionsService.getContributionsByMember(this.selectedTarget.id);
    stream.subscribe({
      next: (rows) => {
        this.contributions = rows
          .filter(c => c.status !== 'paid' && c.status !== 'cancelled')
          .sort((a, b) => (b.periodStart || '').localeCompare(a.periodStart || ''));
        this.loadingContributions = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load contributions';
        this.loadingContributions = false;
      },
    });
  }

  selectedMethod(): PaymentMethod | undefined {
    return this.paymentMethods.find(p => p.code === this.form.paymentMethodCode);
  }

  // ── Submit ─────────────────────────────────────────────────────────────

  submit(): void {
    if (!this.selectedTarget) {
      this.errorMessage = 'Pick a target (group or individual) to record the transaction against';
      return;
    }
    if (!this.form.contributionId) {
      this.errorMessage = 'Pick a contribution to apply this payment to';
      return;
    }
    if (!this.form.amount || !this.form.currencyCode || !this.form.transactionTypeCode) {
      this.errorMessage = 'Amount, currency, and transaction type are required';
      return;
    }
    const method = this.selectedMethod();
    if (method?.requiresReference && !this.form.reference.trim()) {
      this.errorMessage = `${method.label} requires a reference`;
      return;
    }

    const payload: RecordTransactionPayload = {
      contributionId: this.form.contributionId,
      amount: this.form.amount,
      currencyCode: this.form.currencyCode,
      transactionType: this.form.transactionTypeCode,
      paymentMethod: this.form.paymentMethodCode || undefined,
      reference: this.form.reference.trim() || undefined,
    };

    this.saving = true;
    this.errorMessage = null;
    this.contributionsService.recordTransaction(payload).subscribe({
      next: () => {
        this.saving = false;
        this.successMessage = 'Transaction recorded';
        setTimeout(() => this.router.navigate(['/tenant/billing/transactions']), 800);
      },
      error: (err) => {
        this.saving = false;
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Save failed';
      },
    });
  }
}
