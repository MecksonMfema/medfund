import { CommonModule } from '@angular/common';
import { Component, EventEmitter, HostListener, Input, Output, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';
import {
  CreateEndorsementPayload,
  EndorsementChangeType,
} from '../../../../core/services/endorsement.service';
import { CurrencyService, TenantCurrencyConfig } from '../../../../core/services/currency.service';
import { TenantService } from '../../../../core/services/tenant.service';

/**
 * Draft-endorsement modal (Phase 12 §C). Wraps
 * {@link EndorsementService.create} — the parent owns the HTTP call so
 * the modal stays presentation-only. Effective-date input snaps to the
 * 1st of the month on blur per {@code feedback_effective_date_snap}; the
 * backend also snaps but the client snap keeps the form value in sync
 * with what the server will actually persist. Reason is validated to
 * ≥ 10 chars client-side to match user-service's controller-layer
 * message; the DB constraint bounds it at ≥ 1 char, so the client
 * check is stricter but user-friendlier than the 400 the server
 * returns for shorter reasons.
 */
@Component({
  selector: 'app-endorsement-modal',
  standalone: true,
  imports: [CommonModule, FormsModule, SelectComponent],
  templateUrl: './endorsement-modal.component.html',
  styleUrl: './endorsement-modal.component.scss',
})
export class EndorsementModalComponent implements OnInit {
  @Input() policyId = '';
  @Input() policySource = '';
  @Input() insuranceLine = '';
  @Input() policyLabel = '';
  @Input() open = false;

  @Output() cancel = new EventEmitter<void>();
  @Output() submit = new EventEmitter<CreateEndorsementPayload>();

  changeType: EndorsementChangeType = 'PREMIUM_ADJUSTMENT';
  effectiveFrom = this.firstOfMonthOffset(1);
  premiumDelta: number | null = null;
  currencyCode = '';
  reason = '';
  error: string | null = null;

  currencies: TenantCurrencyConfig[] = [];

  readonly changeTypes: SelectOption[] = [
    { value: 'PREMIUM_ADJUSTMENT',  label: 'Premium adjustment' },
    { value: 'COVERAGE_EXTENSION',  label: 'Coverage extension' },
    { value: 'BENEFIT_CHANGE',      label: 'Benefit change' },
    { value: 'BENEFICIARY_CHANGE',  label: 'Beneficiary change' },
    { value: 'ADMIN_CHANGE',        label: 'Admin / non-financial change' },
    { value: 'PRODUCT_SWITCH',      label: 'Product switch' },
    { value: 'RENEWAL_ADVANCE',     label: 'Renewal advance' },
  ];

  constructor(
    private currencyService: CurrencyService,
    private tenantService: TenantService,
  ) {}

  ngOnInit(): void {
    const tenantId = this.tenantService.getTenantId();
    if (!tenantId) return;
    this.currencyService.listForTenant(tenantId).subscribe({
      next: cs => {
        this.currencies = cs.filter(c => c.isActive);
        const def = this.currencies.find(c => c.isDefault);
        if (def && !this.currencyCode) this.currencyCode = def.currencyCode;
      },
      error: () => { /* non-fatal */ },
    });
  }

  get currencyOptions(): SelectOption[] {
    return [
      { value: '', label: '— Select currency —' },
      ...this.currencies.map(c => ({
        value: c.currencyCode,
        label: `${c.currencyCode}${c.isDefault ? ' (default)' : ''}`,
      })),
    ];
  }

  onEffectiveDateChange(): void {
    if (!this.effectiveFrom) return;
    const parts = this.effectiveFrom.split('-');
    if (parts.length === 3) {
      this.effectiveFrom = `${parts[0]}-${parts[1]}-01`;
    }
  }

  isFinancial(): boolean {
    return this.changeType === 'PREMIUM_ADJUSTMENT'
        || this.changeType === 'COVERAGE_EXTENSION'
        || this.changeType === 'PRODUCT_SWITCH'
        || this.changeType === 'RENEWAL_ADVANCE';
  }

  onSubmit(): void {
    this.error = null;
    if (!this.policyId || !this.policySource || !this.insuranceLine) {
      this.error = 'Policy context missing — refresh and try again.';
      return;
    }
    if (!/^\d{4}-\d{2}-\d{2}$/.test(this.effectiveFrom)) {
      this.error = 'Effective date must be YYYY-MM-DD (1st of month).';
      return;
    }
    if (this.reason.trim().length < 10) {
      this.error = 'Reason must be at least 10 characters.';
      return;
    }
    // The server validates delta+currency pairing; keep the client
    // check narrow — if a premium delta is set, a currency must be too.
    if (this.premiumDelta != null && !this.currencyCode) {
      this.error = 'Currency is required when premium delta is set.';
      return;
    }
    this.submit.emit({
      policyId:      this.policyId,
      policySource:  this.policySource,
      insuranceLine: this.insuranceLine,
      changeType:    this.changeType,
      effectiveFrom: this.effectiveFrom,
      premiumDelta:  this.premiumDelta != null ? String(this.premiumDelta) : null,
      currencyCode:  this.premiumDelta != null ? this.currencyCode : null,
      reason:        this.reason.trim(),
    });
  }

  onCancel(): void { this.cancel.emit(); }

  @HostListener('document:keydown.escape')
  onEscape(): void { if (this.open) this.onCancel(); }

  reset(): void {
    this.changeType = 'PREMIUM_ADJUSTMENT';
    this.effectiveFrom = this.firstOfMonthOffset(1);
    this.premiumDelta = null;
    this.reason = '';
    this.error = null;
  }

  private firstOfMonthOffset(offset: number): string {
    const d = new Date();
    d.setDate(1);
    d.setMonth(d.getMonth() + offset);
    return d.toISOString().slice(0, 10);
  }
}
