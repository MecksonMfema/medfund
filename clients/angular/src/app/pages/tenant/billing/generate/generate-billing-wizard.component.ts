import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import {
  ContributionsService,
  Scheme,
  BillingPreviewResponse,
  BillingFilterPayload,
} from '../../../../core/services/contributions.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { CurrencyFormatPipe } from '../../../../shared/pipes/currency-format.pipe';
import { HumanizePipe } from '../../../../shared/pipes/humanize.pipe';

type WizardStep = 'filters' | 'preview' | 'committed';

@Component({
  selector: 'app-generate-billing-wizard',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent, CurrencyFormatPipe, HumanizePipe],
  templateUrl: './generate-billing-wizard.component.html',
  styleUrl: './generate-billing-wizard.component.scss',
})
export class GenerateBillingWizardComponent implements OnInit {
  step: WizardStep = 'filters';
  errorMessage: string | null = null;
  loading = false;
  saving = false;

  schemes: Scheme[] = [];
  selectedSchemeIds: string[] = [];

  // Period defaults to current month.
  periodStart = '';
  periodEnd = '';

  preview: BillingPreviewResponse | null = null;
  committedAt: string | null = null;
  committedCount = 0;
  committedTotals: Record<string, string> = {};

  constructor(private contributions: ContributionsService) {}

  ngOnInit(): void {
    const today = new Date();
    const start = new Date(today.getFullYear(), today.getMonth(), 1);
    const end = new Date(today.getFullYear(), today.getMonth() + 1, 0);
    this.periodStart = start.toISOString().slice(0, 10);
    this.periodEnd = end.toISOString().slice(0, 10);

    this.contributions.getSchemes().subscribe({
      next: (s) => { this.schemes = s; },
      error: (err) => { this.errorMessage = err?.error?.detail || 'Failed to load schemes'; },
    });
  }

  toggleScheme(id: string): void {
    const idx = this.selectedSchemeIds.indexOf(id);
    if (idx === -1) this.selectedSchemeIds = [...this.selectedSchemeIds, id];
    else this.selectedSchemeIds = this.selectedSchemeIds.filter(x => x !== id);
  }

  isSchemeSelected(id: string): boolean {
    return this.selectedSchemeIds.includes(id);
  }

  totalsKeys(map: Record<string, string> | undefined): string[] {
    return map ? Object.keys(map) : [];
  }

  runPreview(): void {
    if (!this.periodStart || !this.periodEnd) {
      this.errorMessage = 'Pick a period';
      return;
    }
    this.loading = true;
    this.errorMessage = null;
    const filters: BillingFilterPayload = {
      periodStart: this.periodStart,
      periodEnd: this.periodEnd,
      schemeIds: this.selectedSchemeIds.length > 0 ? this.selectedSchemeIds : undefined,
    };
    this.contributions.previewBilling(filters).subscribe({
      next: (resp) => {
        this.preview = resp;
        this.loading = false;
        this.step = 'preview';
      },
      error: (err) => {
        this.loading = false;
        this.errorMessage = err?.error?.detail || 'Preview failed';
      },
    });
  }

  commit(): void {
    if (!this.preview) return;
    this.saving = true;
    this.errorMessage = null;
    const filters: BillingFilterPayload = {
      periodStart: this.periodStart,
      periodEnd: this.periodEnd,
      schemeIds: this.selectedSchemeIds.length > 0 ? this.selectedSchemeIds : undefined,
    };
    this.contributions.commitBilling(filters).subscribe({
      next: (resp) => {
        this.saving = false;
        this.committedCount = resp.contributionsCreated;
        this.committedTotals = resp.totalsByCurrency;
        this.committedAt = resp.committedAt;
        this.step = 'committed';
      },
      error: (err) => {
        this.saving = false;
        const detail = err?.error?.detail || err?.error?.title || 'Commit failed';
        this.errorMessage = detail;
      },
    });
  }

  backToFilters(): void {
    this.step = 'filters';
    this.preview = null;
  }

  startAnother(): void {
    this.step = 'filters';
    this.preview = null;
    this.committedAt = null;
    this.committedCount = 0;
    this.committedTotals = {};
  }
}
