import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subject, debounceTime, distinctUntilChanged, of, switchMap } from 'rxjs';
import {
  EligibilityQuoteRequest,
  EligibilityQuoteResponse,
  EligibilityQuoteService,
} from '../../../../core/services/eligibility-quote.service';
import { MembersService } from '../../../../core/services/members.service';
import {
  ClaimsConfigService,
  TariffCode,
} from '../../../../core/services/claims-config.service';
import { CurrencyService, TenantCurrencyConfig } from '../../../../core/services/currency.service';
import { TenantService } from '../../../../core/services/tenant.service';
import {
  EntityPickerComponent,
  EntityPickerSelection,
} from '../../../../shared/components/entity-picker/entity-picker.component';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';

interface TariffRow {
  code: string;
  matches: TariffCode[];
  searching: boolean;
  showMatches: boolean;
  query$: Subject<string>;
}

/**
 * Point-of-service eligibility quote form (Phase 3). The user picks a
 * member (search-select, per {@code feedback_no_raw_id_inputs}), adds
 * one or more tariff codes with the total billed amount, and hits
 * "Get quote". The backend runs a read-only adjudication and returns
 * the seven cost-share buckets; the result panel renders them inline.
 *
 * <p>Mounted under {@code /tenant/claims/eligibility-quote} so operational
 * staff can look up quotes for members today. When the dedicated provider
 * portal ships this same standalone component can be remounted under
 * {@code /provider/eligibility-quote} without changes.
 */
@Component({
  selector: 'app-eligibility-quote',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    EntityPickerComponent,
    IconComponent,
    SelectComponent,
  ],
  templateUrl: './eligibility-quote.component.html',
  styleUrl: './eligibility-quote.component.scss',
})
export class EligibilityQuoteComponent implements OnInit {
  loading = false;
  errorMessage: string | null = null;
  quote: EligibilityQuoteResponse | null = null;

  memberId: string | null = null;
  memberNumber: string | null = null;
  memberLabel: string | null = null;

  currencies: TenantCurrencyConfig[] = [];

  form = {
    serviceCategory: 'CONSULTATION',
    billedAmount: '',
    currencyCode: 'USD',
    dateOfService: new Date().toISOString().slice(0, 10),
  };

  tariffs: TariffRow[] = [this.blankTariffRow()];

  readonly serviceCategoryOptions: SelectOption[] = [
    { value: 'CONSULTATION', label: 'Consultation' },
    { value: 'PROCEDURE', label: 'Procedure' },
    { value: 'PHARMACY', label: 'Pharmacy' },
    { value: 'IMAGING', label: 'Imaging' },
    { value: 'DENTAL', label: 'Dental' },
    { value: 'OPTICAL', label: 'Optical' },
    { value: 'HOSPITALISATION', label: 'Hospitalisation' },
    { value: 'OTHER', label: 'Other' },
  ];

  get currencyOptions(): SelectOption[] {
    return this.currencies.map(c => ({
      value: c.currencyCode,
      label: c.currencyCode,
      description: c.isDefault ? 'Default' : undefined,
    }));
  }

  get billedAmountNumber(): number {
    const n = parseFloat(this.form.billedAmount);
    return Number.isFinite(n) ? n : 0;
  }

  constructor(
    private service: EligibilityQuoteService,
    private members: MembersService,
    private claimsConfig: ClaimsConfigService,
    private currencyService: CurrencyService,
    private tenantService: TenantService,
  ) {}

  ngOnInit(): void {
    const tenant = this.tenantService.getTenant();
    if (tenant) {
      this.currencyService.listForTenant(tenant.id).subscribe({
        next: (rows) => {
          this.currencies = rows.filter(c => c.isActive);
          const def = this.currencies.find(c => c.isDefault);
          if (def) this.form.currencyCode = def.currencyCode;
        },
      });
    }
    this.wireTariffRow(this.tariffs[0]);
  }

  // ── Member picker ─────────────────────────────────────────────────────
  onMemberPicked(sel: EntityPickerSelection | null): void {
    if (!sel) {
      this.memberId = null;
      this.memberNumber = null;
      this.memberLabel = null;
      return;
    }
    this.memberId = sel.id;
    this.memberLabel = sel.label;
    // The member picker's sublabel is the memberNumber (see EntityPicker
    // search branch for kind='member'). Keep it structured — the request
    // payload carries the memberNumber, never the raw id.
    this.memberNumber = sel.sublabel ?? null;
    if (!this.memberNumber) {
      // Fallback: fetch the full member row when the picker didn't populate
      // sublabel (defensive — happens if the search shape ever changes).
      this.members.getById(sel.id).subscribe({
        next: (m) => { this.memberNumber = m.memberNumber; },
      });
    }
  }

  // ── Tariff-code rows ──────────────────────────────────────────────────
  addTariffRow(): void {
    const row = this.blankTariffRow();
    this.tariffs.push(row);
    this.wireTariffRow(row);
  }

  removeTariffRow(index: number): void {
    if (this.tariffs.length <= 1) return;
    this.tariffs.splice(index, 1);
  }

  onTariffInput(row: TariffRow): void {
    row.showMatches = true;
    row.query$.next(row.code);
  }

  onTariffFocus(row: TariffRow): void { row.showMatches = true; }

  onTariffBlur(row: TariffRow): void {
    setTimeout(() => { row.showMatches = false; }, 150);
  }

  pickTariff(row: TariffRow, t: TariffCode): void {
    row.code = t.code;
    row.matches = [];
    row.showMatches = false;
  }

  private blankTariffRow(): TariffRow {
    return { code: '', matches: [], searching: false, showMatches: false, query$: new Subject<string>() };
  }

  private wireTariffRow(row: TariffRow): void {
    row.query$
      .pipe(
        debounceTime(250),
        distinctUntilChanged(),
        switchMap((q) => {
          const term = q.trim();
          if (!term) { row.searching = false; return of<TariffCode[]>([]); }
          row.searching = true;
          return this.claimsConfig.searchCodes(term);
        }),
      )
      .subscribe({
        next: (rows) => { row.matches = rows.slice(0, 8); row.searching = false; },
        error: () => { row.matches = []; row.searching = false; },
      });
  }

  // ── Submit ────────────────────────────────────────────────────────────
  submit(): void {
    this.errorMessage = null;
    this.quote = null;
    if (!this.memberNumber) { this.errorMessage = 'Pick a member'; return; }
    const codes = this.tariffs.map(r => r.code.trim().toUpperCase()).filter(c => c.length > 0);
    if (codes.length === 0) { this.errorMessage = 'Add at least one tariff code'; return; }
    if (!this.form.billedAmount || this.billedAmountNumber <= 0) {
      this.errorMessage = 'Billed amount must be greater than zero';
      return;
    }

    const request: EligibilityQuoteRequest = {
      memberNumber: this.memberNumber,
      serviceCategory: this.form.serviceCategory,
      tariffCodes: codes,
      billedAmount: this.form.billedAmount,
      currencyCode: this.form.currencyCode,
      dateOfService: this.form.dateOfService,
    };

    this.loading = true;
    this.service.quote(request).subscribe({
      next: (response) => {
        this.loading = false;
        this.quote = response;
      },
      error: (err) => {
        this.loading = false;
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Quote failed';
      },
    });
  }
}
