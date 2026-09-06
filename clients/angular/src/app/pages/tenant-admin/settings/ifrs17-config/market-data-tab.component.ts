import { Component, ElementRef, OnInit, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  AddTenantMarketDataConfig,
  MarketDataSource,
  TenantMarketDataConfigRow,
  TenantMarketDataConfigService,
} from '../../../../core/services/tenant-market-data-config.service';
import { TenantService } from '../../../../core/services/tenant.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';

interface EditableRow extends TenantMarketDataConfigRow {
  pending?: boolean;
}

/**
 * Per-tenant enrolment for the Phase 24 market-data-service yield-curve
 * auto-fetch (Phase 15 §24). One row per (currency, source) — RBZ_AUTO
 * for Zimbabwe, SARB_AUTO for South Africa. Toggling `auto_fetch_enabled`
 * pauses / resumes the daemon's daily fetch without deleting the row.
 * The daemon polls tenancy-service each fetch tick, so a change lands
 * on the next cron firing (no service restart needed).
 */
@Component({
  selector: 'app-ifrs17-market-data-tab',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, SkeletonComponent, SelectComponent],
  templateUrl: './market-data-tab.component.html',
  styleUrl: './ifrs17-tab-shared.scss',
})
export class MarketDataTabComponent implements OnInit {
  rows: EditableRow[] = [];
  loading = false;
  errorMessage: string | null = null;
  successMessage: string | null = null;

  addingOpen = false;
  adding = false;
  newRow: AddTenantMarketDataConfig = this.blankRow();

  @ViewChild('addFormRef') addFormRef?: ElementRef<HTMLElement>;

  readonly sources: Array<{ id: MarketDataSource; label: string; jurisdiction: string }> = [
    { id: 'RBZ_AUTO',  label: 'RBZ (Zimbabwe)',      jurisdiction: 'Reserve Bank of Zimbabwe' },
    { id: 'SARB_AUTO', label: 'SARB (South Africa)', jurisdiction: 'South African Reserve Bank' },
  ];

  /** Options getter feeding the app-select in the enrol form. */
  get sourceOptions(): SelectOption[] {
    return this.sources.map(s => ({ value: s.id, label: s.label }));
  }

  constructor(
    private service: TenantMarketDataConfigService,
    private tenantService: TenantService,
  ) {}

  ngOnInit(): void {
    this.refresh();
  }

  refresh(): void {
    const tenantId = this.tenantService.getTenantId();
    if (!tenantId) {
      this.errorMessage = 'No active tenant context';
      return;
    }
    this.loading = true;
    this.errorMessage = null;
    this.service.list(tenantId).subscribe({
      next: (rows) => {
        this.rows = rows.map(r => ({ ...r }));
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || err?.message || 'Failed to load market-data configs';
        this.loading = false;
      },
    });
  }

  openAdd(): void {
    this.addingOpen = true;
    this.newRow = this.blankRow();
    // Scroll the newly rendered form into view on the next tick so the admin
    // never has to hunt for the Add Row inputs on tenants with a long
    // table above.
    setTimeout(() => {
      const el = this.addFormRef?.nativeElement;
      if (!el) return;
      el.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
      const firstFocusable = el.querySelector<HTMLElement>('button, [role="button"], input, select, textarea');
      firstFocusable?.focus();
    }, 0);
  }

  cancelAdd(): void {
    this.addingOpen = false;
    this.newRow = this.blankRow();
  }

  add(): void {
    const tenantId = this.tenantService.getTenantId();
    if (!tenantId) return;
    if (!this.newRow.currency || !this.newRow.source) {
      this.errorMessage = 'Currency and source are required';
      return;
    }
    this.adding = true;
    this.errorMessage = null;
    const body: AddTenantMarketDataConfig = {
      currency: (this.newRow.currency ?? '').trim().toUpperCase(),
      source: this.newRow.source,
      autoFetchEnabled: this.newRow.autoFetchEnabled ?? true,
    };
    this.service.add(tenantId, body).subscribe({
      next: () => {
        this.adding = false;
        this.addingOpen = false;
        this.successMessage = `Enrolled ${body.currency} for ${body.source}`;
        setTimeout(() => (this.successMessage = null), 3000);
        this.newRow = this.blankRow();
        this.refresh();
      },
      error: (err) => {
        this.adding = false;
        this.errorMessage = err?.error?.detail || 'Failed to enrol market-data config';
      },
    });
  }

  toggleEnabled(row: EditableRow): void {
    const tenantId = this.tenantService.getTenantId();
    if (!tenantId) return;
    row.pending = true;
    const desired = !row.autoFetchEnabled;
    this.service.update(tenantId, row.id, { autoFetchEnabled: desired }).subscribe({
      next: (updated) => {
        Object.assign(row, updated, { pending: false });
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to toggle auto-fetch';
        row.pending = false;
      },
    });
  }

  remove(row: EditableRow): void {
    if (!confirm(`Delete auto-fetch enrolment for ${row.currency} via ${row.source}?`)) return;
    const tenantId = this.tenantService.getTenantId();
    if (!tenantId) return;
    row.pending = true;
    this.service.delete(tenantId, row.id).subscribe({
      next: () => {
        this.rows = this.rows.filter(r => r.id !== row.id);
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to delete market-data config';
        row.pending = false;
      },
    });
  }

  private blankRow(): AddTenantMarketDataConfig {
    return {
      currency: '',
      source: 'RBZ_AUTO',
      autoFetchEnabled: true,
    };
  }
}
