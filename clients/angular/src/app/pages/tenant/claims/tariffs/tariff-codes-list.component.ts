import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import {
  ClaimsConfigService,
  CreateTariffCodePayload,
  TariffCode,
  TariffSchedule,
} from '../../../../core/services/claims-config.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { CurrencyFormatPipe } from '../../../../shared/pipes/currency-format.pipe';

@Component({
  selector: 'app-tariff-codes-list',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent, SelectComponent, SkeletonComponent, CurrencyFormatPipe],
  templateUrl: './tariff-codes-list.component.html',
  styleUrl: './tariff-codes-list.component.scss',
})
export class TariffCodesListComponent implements OnInit {
  scheduleId: string | null = null;
  schedule: TariffSchedule | null = null;
  rows: TariffCode[] = [];
  loading = false;
  saving = false;
  errorMessage: string | null = null;

  // Inline-add form state. We don't bother with a separate page: tariff codes
  // are short rows that mostly get bulk-loaded once and incrementally added.
  showForm = false;
  draft: CreateTariffCodePayload = {
    scheduleId: '',
    code: '',
    description: '',
    category: '',
    unitPrice: '',
    currencyCode: 'USD',
    requiresPreAuth: false,
  };

  readonly currencyOptions: SelectOption[] = [
    { value: 'USD', label: 'USD' },
    { value: 'ZWL', label: 'ZWL' },
    { value: 'ZAR', label: 'ZAR' },
  ];

  constructor(private config: ClaimsConfigService, private route: ActivatedRoute) {}

  ngOnInit(): void {
    this.scheduleId = this.route.snapshot.paramMap.get('scheduleId');
    if (!this.scheduleId) return;
    this.draft.scheduleId = this.scheduleId;
    this.loading = true;
    forkJoin({
      schedule: this.config.getSchedule(this.scheduleId),
      codes: this.config.listCodesBySchedule(this.scheduleId),
    }).subscribe({
      next: ({ schedule, codes }) => {
        this.schedule = schedule;
        this.rows = codes;
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load tariff codes';
        this.loading = false;
      },
    });
  }

  toggleForm(): void {
    this.showForm = !this.showForm;
    if (!this.showForm) this.resetDraft();
  }

  submitDraft(): void {
    if (!this.draft.code.trim() || !this.draft.description.trim() || !this.draft.unitPrice) {
      this.errorMessage = 'Code, description and unit price are required';
      return;
    }
    this.saving = true;
    this.errorMessage = null;
    this.config.createCode({
      scheduleId: this.scheduleId!,
      code: this.draft.code.trim().toUpperCase(),
      description: this.draft.description.trim(),
      category: this.draft.category?.trim() || undefined,
      unitPrice: this.draft.unitPrice,
      currencyCode: this.draft.currencyCode || 'USD',
      requiresPreAuth: this.draft.requiresPreAuth,
    }).subscribe({
      next: (saved) => {
        this.rows = [saved, ...this.rows];
        this.saving = false;
        this.resetDraft();
      },
      error: (err) => {
        this.saving = false;
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Save failed';
      },
    });
  }

  private resetDraft(): void {
    this.draft = {
      scheduleId: this.scheduleId!,
      code: '',
      description: '',
      category: '',
      unitPrice: '',
      currencyCode: 'USD',
      requiresPreAuth: false,
    };
  }
}
