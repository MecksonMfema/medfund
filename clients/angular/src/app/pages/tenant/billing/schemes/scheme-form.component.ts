import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ContributionsService, Scheme, UpsertSchemePayload } from '../../../../core/services/contributions.service';
import { CurrencyService, TenantCurrencyConfig } from '../../../../core/services/currency.service';
import { TenantService } from '../../../../core/services/tenant.service';
import { SchemeTypeOption, schemeTypesForLines, insuranceLineLabel, lineForSchemeType } from '../../../../core/models/insurance-lines';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';
import { ToastService } from '../../../../shared/components/toast/toast.service';

interface SchemeForm {
  name: string;
  description: string;
  schemeType: string;
  effectiveDate: string;
  endDate: string;
  currencyCode: string;
}

@Component({
  selector: 'app-scheme-form',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent, SelectComponent],
  templateUrl: './scheme-form.component.html',
  styleUrl: './scheme-form.component.scss',
})
export class SchemeFormComponent implements OnInit {
  schemeId: string | null = null;
  loading = false;
  saving = false;
  errorMessage: string | null = null;
  successMessage: string | null = null;

  allowedCurrencies: TenantCurrencyConfig[] = [];

  form: SchemeForm = {
    name: '',
    description: '',
    schemeType: '',
    effectiveDate: new Date().toISOString().slice(0, 10),
    endDate: '',
    currencyCode: '',
  };

  /** Snapshot of the form right after the existing scheme loads — used to
   *  disable the Save button when nothing has actually been edited. */
  private originalForm: SchemeForm | null = null;

  /** Dirty-check: true if any field differs from the loaded scheme. On the
   *  add page (no existing snapshot) we treat the form as dirty whenever the
   *  required fields are filled, so the button enables as soon as the user
   *  has provided a name and currency. */
  get isDirty(): boolean {
    if (!this.originalForm) return true;
    const f = this.form, o = this.originalForm;
    return (
      f.name          !== o.name ||
      f.description   !== o.description ||
      f.schemeType    !== o.schemeType ||
      f.effectiveDate !== o.effectiveDate ||
      f.endDate       !== o.endDate ||
      f.currencyCode  !== o.currencyCode
    );
  }

  /** Built from the active tenant's {@code insuranceLines} so e.g. a vehicle
   *  insurer doesn't see Medical aid / HMO in the dropdown. Populated in
   *  ngOnInit; empty briefly during initial render before tenant resolves. */
  schemeTypes: SchemeTypeOption[] = [];

  /** SelectOption[] view of the scheme-type catalogue. Groups by insurance
   *  line when the tenant has more than one line enabled, so a multi-line
   *  carrier sees section headings ("Health Insurance", "Life Insurance", …)
   *  in the dropdown instead of one undifferentiated wall of choices. */
  get schemeTypeSelectOptions(): SelectOption[] {
    const distinctLines = new Set(this.schemeTypes.map(t => t.line));
    const multiLine = distinctLines.size > 1;
    return this.schemeTypes.map(t => ({
      value: t.code,
      label: t.label,
      group: multiLine ? insuranceLineLabel(t.line) : undefined,
    }));
  }

  get currencySelectOptions(): SelectOption[] {
    return this.allowedCurrencies.map(c => ({
      value: c.currencyCode,
      label: c.currencyCode,
      description: c.isDefault ? 'Default' : undefined,
    }));
  }

  constructor(
    private contributions: ContributionsService,
    private currencyService: CurrencyService,
    private tenantService: TenantService,
    private route: ActivatedRoute,
    private router: Router,
    private toast: ToastService,
  ) {}

  ngOnInit(): void {
    const tenantId = this.tenantService.getTenantId();
    if (!tenantId) {
      this.errorMessage = 'No active tenant context';
      return;
    }

    // Build the scheme-type dropdown from the tenant's configured insurance
    // lines. A HEALTH-only tenant sees Medical aid / HMO / ...; a VEHICLE
    // insurer sees Comprehensive / Third-party / Fleet. Default the form to
    // the first item so the field is never left blank on a fresh load.
    const tenant = this.tenantService.getTenant();
    this.schemeTypes = schemeTypesForLines(tenant?.insuranceLines ?? []);
    if (!this.form.schemeType && this.schemeTypes.length > 0) {
      this.form.schemeType = this.schemeTypes[0].code;
    }

    this.currencyService.listForTenant(tenantId).subscribe({
      next: (configs) => {
        this.allowedCurrencies = configs.filter(c => c.isActive && c.isBillingCurrency);
        const def = configs.find(c => c.isDefault);
        if (!this.form.currencyCode && def) this.form.currencyCode = def.currencyCode;
      },
    });

    this.schemeId = this.route.snapshot.paramMap.get('id');
    if (this.schemeId) {
      this.loading = true;
      this.contributions.getSchemeById(this.schemeId).subscribe({
        next: (s) => {
          this.form = {
            name: s.name,
            description: s.description ?? '',
            schemeType: s.schemeType ?? this.schemeTypes[0]?.code ?? '',
            effectiveDate: s.effectiveDate,
            endDate: s.endDate ?? '',
            currencyCode: s.currencyCode ?? '',
          };
          // Take a copy so isDirty can compare current vs original.
          this.originalForm = { ...this.form };
          this.loading = false;
        },
        error: (err) => {
          this.errorMessage = err?.error?.detail || 'Failed to load scheme';
          this.loading = false;
        },
      });
    }
  }

  submit(): void {
    if (!this.form.name.trim()) {
      this.errorMessage = 'Name is required';
      return;
    }
    if (!this.form.currencyCode) {
      this.errorMessage = 'Pick a currency for this scheme';
      return;
    }
    const payload: UpsertSchemePayload = {
      name: this.form.name.trim(),
      description: this.form.description.trim() || undefined,
      schemeType: this.form.schemeType,
      // The user picks a scheme_type (Medical aid, Comprehensive, ...); we
      // derive the insurance line from that pick so Age Groups / Benefits
      // gating downstream knows whether they apply to this scheme.
      insuranceLine: lineForSchemeType(this.form.schemeType),
      effectiveDate: this.form.effectiveDate,
      endDate: this.form.endDate || undefined,
      currencyCode: this.form.currencyCode,
    };
    this.saving = true;
    this.errorMessage = null;
    const stream = this.schemeId
      ? this.contributions.updateScheme(this.schemeId, payload)
      : this.contributions.createScheme(payload);
    const isEdit = !!this.schemeId;
    stream.subscribe({
      next: () => {
        this.saving = false;
        this.toast.success(isEdit ? 'Scheme updated' : 'Scheme created');
        this.router.navigate(['/tenant/billing/schemes']);
      },
      error: (err) => {
        this.saving = false;
        const detail = err?.error?.detail || err?.error?.title || 'Save failed';
        this.errorMessage = detail;
        this.toast.error(detail);
      },
    });
  }
}
