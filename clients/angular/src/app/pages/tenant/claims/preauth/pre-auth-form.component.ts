import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { Subject, debounceTime, distinctUntilChanged, of, switchMap } from 'rxjs';
import {
  PreAuthRequestPayload,
  PreAuthService,
} from '../../../../core/services/pre-auth.service';
import { MembersService } from '../../../../core/services/members.service';
import { ContributionsService } from '../../../../core/services/contributions.service';
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
import { CurrencyFormatPipe } from '../../../../shared/pipes/currency-format.pipe';

/**
 * Pre-authorization request form. Mirrors the visual grammar of
 * {@code submit-claim.component} — sections with icon-badged headers,
 * beneficiary picker (member OR dependant), scheme auto-resolution.
 *
 * <p>The beneficiary picker resolves to {@code (memberId, dependantId)} —
 * memberId is always the sponsor (used for member drill-through / billing)
 * and dependantId is only set when a dependant is picked. The backend keys
 * pre-auth lookup off dependantId when present, so a member and a
 * dependant can each hold their own pre-auth for the same tariff code.
 */
@Component({
  selector: 'app-pre-auth-form',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    RouterLink,
    EntityPickerComponent,
    IconComponent,
    SelectComponent,
    CurrencyFormatPipe,
  ],
  templateUrl: './pre-auth-form.component.html',
  styleUrl: './pre-auth-form.component.scss',
})
export class PreAuthFormComponent implements OnInit {
  saving = false;
  errorMessage: string | null = null;

  // Beneficiary — the picker returns either a member or a dependant
  // and threads the sponsor's id into memberId.
  beneficiaryId: string | null = null;
  memberId: string | null = null;
  dependantId: string | null = null;
  beneficiaryLabel: string | null = null;
  beneficiaryKind: 'MEMBER' | 'DEPENDANT' | null = null;

  providerId: string | null = null;

  // Scheme auto-resolved from beneficiary.
  schemeId: string | null = null;
  schemeName: string | null = null;
  schemeStatus: 'idle' | 'loading' | 'ok' | 'missing' | 'error' = 'idle';

  // Reference data.
  currencies: TenantCurrencyConfig[] = [];

  form = {
    tariffCode: '',
    tariffDescription: '',
    diagnosisCode: '',
    requestedAmount: '',
    currencyCode: 'USD',
    notes: '',
  };

  // Tariff-code autocomplete state.
  tariffMatches: TariffCode[] = [];
  tariffSearching = false;
  showTariffMatches = false;
  private tariffQuery$ = new Subject<string>();

  get currencyOptions(): SelectOption[] {
    return this.currencies.map(c => ({
      value: c.currencyCode,
      label: c.currencyCode,
      description: c.isDefault ? 'Default' : undefined,
    }));
  }

  get requestedAmountNumber(): number {
    const n = parseFloat(this.form.requestedAmount);
    return Number.isFinite(n) ? n : 0;
  }

  constructor(
    private service: PreAuthService,
    private members: MembersService,
    private contributions: ContributionsService,
    private claimsConfig: ClaimsConfigService,
    private currencyService: CurrencyService,
    private tenantService: TenantService,
    private router: Router,
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

    this.tariffQuery$
      .pipe(
        debounceTime(250),
        distinctUntilChanged(),
        switchMap((q) => {
          const term = q.trim();
          if (!term) { this.tariffSearching = false; return of<TariffCode[]>([]); }
          this.tariffSearching = true;
          return this.claimsConfig.searchCodes(term);
        }),
      )
      .subscribe({
        next: (rows) => { this.tariffMatches = rows.slice(0, 8); this.tariffSearching = false; },
        error: () => { this.tariffMatches = []; this.tariffSearching = false; },
      });
  }

  // ── Beneficiary → route to memberId / dependantId + resolve scheme ─
  onBeneficiaryPicked(sel: EntityPickerSelection | null): void {
    this.beneficiaryId = sel?.id ?? null;
    this.beneficiaryLabel = sel?.label ?? null;

    if (!sel) {
      this.memberId = null;
      this.dependantId = null;
      this.beneficiaryKind = null;
      this.clearScheme();
      return;
    }

    const b = sel.beneficiary;
    if (!b) {
      // Defensive — kind='beneficiary' always populates b, but guard so a
      // future picker change doesn't silently drop the memberId.
      this.memberId = sel.id;
      this.dependantId = null;
      this.beneficiaryKind = 'MEMBER';
    } else {
      this.memberId = b.memberId;
      this.dependantId = b.dependantId;
      this.beneficiaryKind = b.kind;
    }

    this.resolveSchemeForMember(this.memberId!);
  }

  private clearScheme(): void {
    this.schemeId = null;
    this.schemeName = null;
    this.schemeStatus = 'idle';
  }

  private resolveSchemeForMember(memberId: string): void {
    this.schemeStatus = 'loading';
    this.members.getById(memberId).subscribe({
      next: (member) => {
        if (!member.schemeId) {
          this.schemeStatus = 'missing';
          this.schemeId = null;
          this.schemeName = null;
          return;
        }
        this.contributions.getSchemeById(member.schemeId).subscribe({
          next: (scheme) => {
            this.schemeId = scheme.id;
            this.schemeName = scheme.name;
            this.schemeStatus = 'ok';
            if (scheme.currencyCode) this.form.currencyCode = scheme.currencyCode;
          },
          error: () => {
            this.schemeStatus = 'error';
            this.schemeId = null;
            this.schemeName = null;
          },
        });
      },
      error: () => {
        this.schemeStatus = 'error';
        this.schemeId = null;
        this.schemeName = null;
      },
    });
  }

  // ── Tariff-code autocomplete ────────────────────────────────────────
  onTariffCodeInput(): void {
    this.showTariffMatches = true;
    this.tariffQuery$.next(this.form.tariffCode);
  }

  onTariffCodeFocus(): void { this.showTariffMatches = true; }

  onTariffCodeBlur(): void {
    // Delay so a mousedown on a suggestion still fires.
    setTimeout(() => { this.showTariffMatches = false; }, 150);
  }

  pickTariff(t: TariffCode): void {
    this.form.tariffCode = t.code;
    this.form.tariffDescription = t.description || '';
    if (t.currencyCode) this.form.currencyCode = t.currencyCode;
    if (t.unitPrice != null && !this.form.requestedAmount) {
      this.form.requestedAmount = String(t.unitPrice);
    }
    this.tariffMatches = [];
    this.showTariffMatches = false;
  }

  // ── Submit ─────────────────────────────────────────────────────────
  submit(): void {
    this.errorMessage = null;
    if (!this.memberId) { this.errorMessage = 'Pick a member or dependant'; return; }
    if (!this.providerId) { this.errorMessage = 'Pick a provider'; return; }
    if (!this.schemeId) { this.errorMessage = 'Beneficiary is not enrolled on a scheme'; return; }
    if (!this.form.tariffCode.trim()) { this.errorMessage = 'Tariff code is required'; return; }
    if (!this.form.requestedAmount || this.requestedAmountNumber <= 0) {
      this.errorMessage = 'Requested amount must be greater than zero';
      return;
    }

    const payload: PreAuthRequestPayload = {
      memberId: this.memberId,
      dependantId: this.dependantId,
      providerId: this.providerId,
      schemeId: this.schemeId,
      tariffCode: this.form.tariffCode.trim().toUpperCase(),
      diagnosisCode: this.form.diagnosisCode.trim() || undefined,
      requestedAmount: this.form.requestedAmount,
      currencyCode: this.form.currencyCode,
      notes: this.form.notes.trim() || undefined,
    };

    this.saving = true;
    this.service.create(payload).subscribe({
      next: (saved) => {
        this.saving = false;
        this.router.navigate(['/tenant/claims/preauth', saved.id]);
      },
      error: (err) => {
        this.saving = false;
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Save failed';
      },
    });
  }
}
