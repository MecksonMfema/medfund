import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { forkJoin, of } from 'rxjs';
import {
  SchemeChangeWaitingPeriod,
  WaitingPeriodService,
  UpsertSchemeChangeWaitingPeriodPayload,
} from '../../../../core/services/waiting-period.service';
import {
  BillingCatalogueService,
  BenefitType,
} from '../../../../core/services/billing-catalogue.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';

@Component({
  selector: 'app-scheme-change-waiting-period-form',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent, SelectComponent],
  templateUrl: './scheme-change-waiting-period-form.component.html',
  styleUrl: './scheme-change-waiting-period-form.component.scss',
})
export class SchemeChangeWaitingPeriodFormComponent implements OnInit {
  ruleId: string | null = null;
  benefitTypes: BenefitType[] = [];
  loading = false;
  saving = false;
  errorMessage: string | null = null;

  form: {
    changeType: 'UPGRADE' | 'DOWNGRADE';
    benefitType: string;
    waitingDays: number;
    description: string;
    isActive: boolean;
  } = {
    changeType: 'UPGRADE',
    benefitType: '',
    waitingDays: 30,
    description: '',
    isActive: true,
  };

  readonly changeTypeOptions: SelectOption[] = [
    { value: 'UPGRADE', label: 'Upgrade' },
    { value: 'DOWNGRADE', label: 'Downgrade' },
  ];

  constructor(
    private waitingService: WaitingPeriodService,
    private catalogue: BillingCatalogueService,
    private route: ActivatedRoute,
    private router: Router,
  ) {}

  ngOnInit(): void {
    this.ruleId = this.route.snapshot.paramMap.get('id');
    this.loading = true;
    forkJoin({
      benefitTypes: this.catalogue.listBenefitTypes(true),
      existing: this.ruleId
        ? this.waitingService.listSchemeChange()
        : of<SchemeChangeWaitingPeriod[]>([]),
    }).subscribe({
      next: ({ benefitTypes, existing }) => {
        this.benefitTypes = benefitTypes;
        if (this.ruleId) {
          const found = existing.find(r => r.id === this.ruleId);
          if (found) {
            this.form = {
              changeType: found.changeType,
              benefitType: found.benefitType ?? '',
              waitingDays: found.waitingDays,
              description: found.description ?? '',
              isActive: found.isActive,
            };
          } else {
            this.errorMessage = 'Rule not found';
          }
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
    if (this.form.waitingDays < 0) {
      this.errorMessage = 'Waiting days cannot be negative';
      return;
    }
    const payload: UpsertSchemeChangeWaitingPeriodPayload = {
      changeType: this.form.changeType,
      benefitType: this.form.benefitType.trim() || undefined,
      waitingDays: this.form.waitingDays,
      description: this.form.description.trim() || undefined,
      isActive: this.form.isActive,
    };
    this.saving = true;
    this.errorMessage = null;
    const stream = this.ruleId
      ? this.waitingService.updateSchemeChange(this.ruleId, payload)
      : this.waitingService.createSchemeChange(payload);
    stream.subscribe({
      next: () => {
        this.saving = false;
        this.router.navigate(['/tenant/billing/scheme-change-waiting-periods']);
      },
      error: (err) => {
        this.saving = false;
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Save failed';
      },
    });
  }
}
