import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { forkJoin, of } from 'rxjs';
import {
  ContributionsService,
  Scheme,
} from '../../../../core/services/contributions.service';
import {
  WaitingPeriod,
  WaitingPeriodService,
  UpsertWaitingPeriodPayload,
} from '../../../../core/services/waiting-period.service';
import {
  BillingCatalogueService,
  BenefitType,
} from '../../../../core/services/billing-catalogue.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';

@Component({
  selector: 'app-waiting-period-form',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent, SelectComponent],
  templateUrl: './waiting-period-form.component.html',
  styleUrl: './waiting-period-form.component.scss',
})
export class WaitingPeriodFormComponent implements OnInit {
  ruleId: string | null = null;
  schemes: Scheme[] = [];
  benefitTypes: BenefitType[] = [];
  loading = false;
  saving = false;
  errorMessage: string | null = null;

  form: {
    schemeId: string;
    conditionType: string;
    waitingDays: number;
    description: string;
    /** V052 age-band scoping. Null = universal. */
    minAge: number | null;
    maxAge: number | null;
  } = {
    schemeId: '',
    conditionType: '',
    waitingDays: 30,
    description: '',
    minAge: null,
    maxAge: null,
  };

  get schemeOptions(): SelectOption[] {
    return this.schemes.map(s => ({ value: s.id, label: s.name }));
  }

  constructor(
    private waitingService: WaitingPeriodService,
    private contributions: ContributionsService,
    private catalogue: BillingCatalogueService,
    private route: ActivatedRoute,
    private router: Router,
  ) {}

  ngOnInit(): void {
    this.ruleId = this.route.snapshot.paramMap.get('id');
    this.loading = true;
    forkJoin({
      schemes: this.contributions.getSchemes(),
      benefitTypes: this.catalogue.listBenefitTypes(true),
      existing: this.ruleId
        ? this.waitingService.list().pipe()
        : of<WaitingPeriod[]>([]),
    }).subscribe({
      next: ({ schemes, benefitTypes, existing }) => {
        this.schemes = schemes;
        this.benefitTypes = benefitTypes;
        if (this.ruleId) {
          const found = existing.find(r => r.id === this.ruleId);
          if (found) {
            this.form = {
              schemeId: found.schemeId,
              conditionType: found.conditionType,
              waitingDays: found.waitingDays,
              description: found.description ?? '',
              minAge: (found as any).minAge ?? null,
              maxAge: (found as any).maxAge ?? null,
            };
          } else {
            this.errorMessage = 'Waiting period rule not found';
          }
        } else if (schemes.length > 0 && !this.form.schemeId) {
          this.form.schemeId = schemes[0].id;
        }
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load form data';
        this.loading = false;
      },
    });
  }

  submit(): void {
    if (!this.form.schemeId || !this.form.conditionType.trim()) {
      this.errorMessage = 'Pick a scheme and a condition';
      return;
    }
    const payload: UpsertWaitingPeriodPayload = {
      schemeId: this.form.schemeId,
      conditionType: this.form.conditionType.trim(),
      waitingDays: this.form.waitingDays,
      description: this.form.description.trim() || undefined,
      minAge: this.form.minAge ?? undefined,
      maxAge: this.form.maxAge ?? undefined,
    };
    this.saving = true;
    this.errorMessage = null;
    const stream = this.ruleId
      ? this.waitingService.update(this.ruleId, payload)
      : this.waitingService.create(payload);
    stream.subscribe({
      next: () => {
        this.saving = false;
        this.router.navigate(['/tenant/billing/waiting-periods']);
      },
      error: (err) => {
        this.saving = false;
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Save failed';
      },
    });
  }
}
