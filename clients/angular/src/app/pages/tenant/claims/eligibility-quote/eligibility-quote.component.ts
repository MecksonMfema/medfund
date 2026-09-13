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
import { ToastService } from '../../../../shared/components/toast/toast.service';
import { extractErrorMessage } from '../../../../core/util/http-errors';

interface TariffRow {
  /** The tariff code submitted to the quote endpoint. */
  code: string;
  /** Human-readable description surfaced next to the code once picked
   *  so the operator sees what they selected instead of a bare code. */
  description: string;
  /** Free-text search entered while the row is in search mode. Cleared
   *  the moment a suggestion is picked (which populates {@link code}
   *  + {@link description} instead). */
  query: string;
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
  quote: EligibilityQuoteResponse | null = null;

  /** Picker's own value (member id or dependant id). Bound to the entity
   *  picker's [value] so it can render the picked chip on first paint. */
  beneficiaryId: string | null = null;
  /** Sponsoring member's UUID. When the operator picks a member this
   *  equals {@link beneficiaryId}; when they pick a dependant it holds
   *  the sponsor's UUID (from {@code BeneficiaryPick.memberId}). */
  sponsorMemberId: string | null = null;
  /** Sponsor's friendly memberNumber — always the sponsor's number, per
   *  the backend wire (memberNumber names the sponsor even in the
   *  dependant case). */
  sponsorMemberNumber: string | null = null;
  /** Set only when the operator picked a dependant. Threaded through
   *  to the backend to key the accumulator read on dependant_id. */
  dependantId: string | null = null;
  pickedLabel: string | null = null;
  pickedKind: 'MEMBER' | 'DEPENDANT' | null = null;

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
    private toast: ToastService,
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

  // ── Beneficiary picker (member OR dependant) ──────────────────────────
  onBeneficiaryPicked(sel: EntityPickerSelection | null): void {
    this.beneficiaryId = sel?.id ?? null;
    if (!sel) {
      this.sponsorMemberId = null;
      this.sponsorMemberNumber = null;
      this.dependantId = null;
      this.pickedLabel = null;
      this.pickedKind = null;
      return;
    }
    const b = sel.beneficiary;
    this.pickedLabel = sel.label;
    if (!b) {
      // Defensive fallback — should never happen when kind='beneficiary'.
      // Treat as a bare member pick and hope the search shape is intact.
      this.sponsorMemberId = sel.id;
      this.sponsorMemberNumber = sel.sublabel ?? null;
      this.dependantId = null;
      this.pickedKind = 'MEMBER';
      return;
    }
    this.pickedKind = b.kind;
    this.sponsorMemberId = b.memberId;
    if (b.kind === 'DEPENDANT') {
      // Backend needs the SPONSOR's memberNumber even for dependant
      // quotes; the picker gives us that via BeneficiaryPick.sponsorMemberNumber
      // (surfaced from the member row via the beneficiary search).
      this.sponsorMemberNumber = b.sponsorMemberNumber ?? null;
      this.dependantId = b.dependantId;
    } else {
      // MEMBER pick — beneficiary id IS the member id; the sublabel is
      // formatted as "MEM · <memberNumber>", so we extract the number.
      this.sponsorMemberNumber = extractMemberNumber(sel.sublabel);
      this.dependantId = null;
    }
    // Defensive backfill: if we somehow ended up without a memberNumber,
    // fetch it (happens if the beneficiary search ever drops the field).
    if (!this.sponsorMemberNumber && this.sponsorMemberId) {
      this.members.getById(this.sponsorMemberId).subscribe({
        next: (m) => { this.sponsorMemberNumber = m.memberNumber; },
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
    row.query$.next(row.query);
  }

  onTariffFocus(row: TariffRow): void { row.showMatches = true; }

  onTariffBlur(row: TariffRow): void {
    setTimeout(() => { row.showMatches = false; }, 150);
  }

  pickTariff(row: TariffRow, t: TariffCode): void {
    row.code = t.code;
    row.description = t.description;
    row.query = '';
    row.matches = [];
    row.showMatches = false;
  }

  /** Drop the current pick and return the row to search mode so the
   *  operator can pick a different code. */
  clearTariff(row: TariffRow): void {
    row.code = '';
    row.description = '';
    row.query = '';
    row.matches = [];
    row.showMatches = false;
  }

  private blankTariffRow(): TariffRow {
    return {
      code: '', description: '', query: '',
      matches: [], searching: false, showMatches: false,
      query$: new Subject<string>(),
    };
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
    this.quote = null;
    if (!this.sponsorMemberNumber) {
      this.toast.warning('Pick a member or dependant');
      return;
    }
    const codes = this.tariffs.map(r => r.code.trim().toUpperCase()).filter(c => c.length > 0);
    if (codes.length === 0) { this.toast.warning('Add at least one tariff code'); return; }
    if (!this.form.billedAmount || this.billedAmountNumber <= 0) {
      this.toast.warning('Billed amount must be greater than zero');
      return;
    }

    const request: EligibilityQuoteRequest = {
      memberNumber: this.sponsorMemberNumber,
      dependantId: this.dependantId ?? undefined,
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
        this.toast.error(extractErrorMessage(err, 'Quote failed'));
      },
    });
  }
}

/**
 * Pull the plain memberNumber out of the beneficiary picker's sublabel.
 * The picker formats MEMBER hits as {@code "MEM · MBR-000123"} (see
 * {@link EntityPickerComponent}'s beneficiary search branch). We slice
 * off the {@code "MEM · "} prefix; if the format ever changes and
 * doesn't match, we fall back to the whole sublabel so submit still
 * has something to send.
 */
function extractMemberNumber(sublabel: string | undefined): string | null {
  if (!sublabel) return null;
  const match = sublabel.match(/^MEM\s*·\s*(.+)$/);
  return match ? match[1].trim() : sublabel;
}
