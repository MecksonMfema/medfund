import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ContributionsService, Scheme, UpsertSchemePayload } from '../../../../core/services/contributions.service';
import { CurrencyService, TenantCurrencyConfig } from '../../../../core/services/currency.service';
import { TenantService } from '../../../../core/services/tenant.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';

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
  imports: [CommonModule, FormsModule, RouterLink, IconComponent],
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
    schemeType: 'medical_aid',
    effectiveDate: new Date().toISOString().slice(0, 10),
    endDate: '',
    currencyCode: '',
  };

  readonly schemeTypes = [
    { code: 'medical_aid',     label: 'Medical aid' },
    { code: 'health_insurance', label: 'Health insurance' },
    { code: 'hmo',             label: 'HMO' },
    { code: 'wellness',        label: 'Wellness' },
  ];

  constructor(
    private contributions: ContributionsService,
    private currencyService: CurrencyService,
    private tenantService: TenantService,
    private route: ActivatedRoute,
    private router: Router,
  ) {}

  ngOnInit(): void {
    const tenantId = this.tenantService.getTenantId();
    if (!tenantId) {
      this.errorMessage = 'No active tenant context';
      return;
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
            schemeType: s.schemeType ?? 'medical_aid',
            effectiveDate: s.effectiveDate,
            endDate: s.endDate ?? '',
            currencyCode: s.currencyCode ?? '',
          };
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
      effectiveDate: this.form.effectiveDate,
      endDate: this.form.endDate || undefined,
      currencyCode: this.form.currencyCode,
    };
    this.saving = true;
    this.errorMessage = null;
    const stream = this.schemeId
      ? this.contributions.updateScheme(this.schemeId, payload)
      : this.contributions.createScheme(payload);
    stream.subscribe({
      next: (saved) => {
        this.saving = false;
        this.successMessage = this.schemeId ? 'Scheme updated' : 'Scheme created';
        if (!this.schemeId) {
          this.router.navigate(['/tenant/billing/schemes', saved.id, 'edit']);
        }
      },
      error: (err) => {
        this.saving = false;
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Save failed';
      },
    });
  }
}
