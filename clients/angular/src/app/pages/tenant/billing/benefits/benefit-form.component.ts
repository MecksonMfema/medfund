import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { forkJoin, of } from 'rxjs';
import {
  ContributionsService,
  Scheme,
  SchemeBenefit,
  UpsertBenefitPayload,
} from '../../../../core/services/contributions.service';
import {
  BillingCatalogueService,
  BenefitType,
} from '../../../../core/services/billing-catalogue.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';

interface BenefitForm {
  name: string;
  benefitType: string;
  annualLimit: string;
  dailyLimit: string;
  eventLimit: string;
  waitingPeriodDays: number | null;
  description: string;
  /** V051 benefit-age gate. Null = unbounded. */
  minAge: number | null;
  maxAge: number | null;
  /** V051 payout-mode flag. Default true (backwards-compat). */
  cashClaimAllowed: boolean;
}

@Component({
  selector: 'app-benefit-form',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent, SelectComponent],
  templateUrl: './benefit-form.component.html',
  styleUrl: './benefit-form.component.scss',
})
export class BenefitFormComponent implements OnInit {
  schemeId = '';
  benefitId: string | null = null;
  scheme: Scheme | null = null;
  benefitTypes: BenefitType[] = [];
  loading = false;
  saving = false;
  errorMessage: string | null = null;

  form: BenefitForm = {
    name: '',
    benefitType: '',
    annualLimit: '',
    dailyLimit: '',
    eventLimit: '',
    waitingPeriodDays: 0,
    description: '',
    minAge: null,
    maxAge: null,
    cashClaimAllowed: true,
  };

  constructor(
    private contributions: ContributionsService,
    private catalogue: BillingCatalogueService,
    private route: ActivatedRoute,
    private router: Router,
  ) {}

  ngOnInit(): void {
    this.schemeId = this.route.snapshot.paramMap.get('schemeId') ?? '';
    this.benefitId = this.route.snapshot.paramMap.get('id');
    if (!this.schemeId) {
      this.errorMessage = 'No scheme id in route';
      return;
    }

    this.loading = true;
    forkJoin({
      scheme: this.contributions.getSchemeById(this.schemeId),
      benefitTypes: this.catalogue.listBenefitTypes(true),
      existing: this.benefitId
        ? this.contributions.getBenefitById(this.benefitId)
        : of<SchemeBenefit | null>(null),
    }).subscribe({
      next: ({ scheme, benefitTypes, existing }) => {
        this.scheme = scheme;
        this.benefitTypes = benefitTypes;
        if (existing) {
          this.form = {
            name: existing.name,
            benefitType: existing.benefitType,
            annualLimit: existing.annualLimit ?? '',
            dailyLimit: existing.dailyLimit ?? '',
            eventLimit: existing.eventLimit ?? '',
            waitingPeriodDays: existing.waitingPeriodDays ?? 0,
            description: existing.description ?? '',
            minAge: (existing as any).minAge ?? null,
            maxAge: (existing as any).maxAge ?? null,
            cashClaimAllowed: (existing as any).cashClaimAllowed ?? true,
          };
        }
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load form data';
        this.loading = false;
      },
    });
  }

  /** When a catalogue type is picked, default the name to the catalogue label so users don't have to retype it. */
  onTypeChange(label: string): void {
    if (!this.form.name.trim() || this.benefitTypes.some(t => t.label === this.form.name)) {
      this.form.name = label;
    }
    this.form.benefitType = label;
  }

  get benefitTypeSelectOptions(): SelectOption[] {
    return this.benefitTypes.map(t => ({ value: t.label, label: t.label }));
  }

  submit(): void {
    if (!this.form.name.trim()) {
      this.errorMessage = 'Name is required';
      return;
    }
    if (!this.form.benefitType.trim()) {
      this.errorMessage = 'Pick a benefit type';
      return;
    }
    const payload: UpsertBenefitPayload = {
      schemeId: this.schemeId,
      name: this.form.name.trim(),
      benefitType: this.form.benefitType.trim(),
      annualLimit: this.form.annualLimit || undefined,
      dailyLimit: this.form.dailyLimit || undefined,
      eventLimit: this.form.eventLimit || undefined,
      waitingPeriodDays: this.form.waitingPeriodDays ?? 0,
      description: this.form.description.trim() || undefined,
      currencyCode: this.scheme?.currencyCode,
      minAge: this.form.minAge ?? undefined,
      maxAge: this.form.maxAge ?? undefined,
      cashClaimAllowed: this.form.cashClaimAllowed,
    };

    this.saving = true;
    this.errorMessage = null;
    const stream = this.benefitId
      ? this.contributions.updateBenefit(this.benefitId, payload)
      : this.contributions.createBenefit(payload);
    stream.subscribe({
      next: () => {
        this.saving = false;
        this.router.navigate(['/tenant/billing/schemes', this.schemeId, 'benefits']);
      },
      error: (err) => {
        this.saving = false;
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Save failed';
      },
    });
  }
}
