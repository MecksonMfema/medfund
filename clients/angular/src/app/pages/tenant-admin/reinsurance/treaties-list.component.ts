import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import {
  ReinsuranceService,
  Treaty,
  TreatyStatus,
} from '../../../core/services/reinsurance.service';
import { DataTableComponent, TableColumn } from '../../../shared/components/data-table/data-table.component';
import { IconComponent } from '../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../shared/components/select/select.component';

/**
 * Reinsurance treaties list. Layout mirrors /tenant/billing/transactions:
 * page-header banner, flush filter strip toolbar, then a full-bleed
 * <app-data-table>. Route uses fullbleed: true so the parent layout does
 * not wrap the page in its own padding.
 *
 * The pre-refactor "grouped by renewal chain" layout collapsed into a
 * single Renewal-chain column (root treatyRef). Sort inception DESC by
 * default; operators can scan a treaty family by sorting on that column.
 */
interface TreatyRow extends Treaty {
  renewalChain: string;
  period: string;
  aggregateLimitDisplay: string;
}

@Component({
  selector: 'app-treaties-list',
  standalone: true,
  imports: [
    CommonModule, FormsModule, RouterLink,
    DataTableComponent, IconComponent, SelectComponent,
  ],
  templateUrl: './treaties-list.component.html',
  styleUrl: './treaties-list.component.scss',
})
export class TreatiesListComponent implements OnInit {
  rows: TreatyRow[] = [];
  loading = false;
  errorMessage: string | null = null;

  // Filter state
  selectedStatus: '' | TreatyStatus = '';
  selectedType = '';
  selectedCurrency = '';
  searchTerm = '';

  // Filter catalogue derived from the loaded rows (backend does not
  // expose enum endpoints for these). Keeps the picker aligned with
  // what actually exists in the tenant.
  currencies: string[] = [];

  columns: TableColumn[] = [
    { key: 'treatyRef',              label: 'Ref',            sortable: true },
    { key: 'renewalChain',           label: 'Renewal chain',  sortable: true },
    { key: 'treatyType',             label: 'Type',           sortable: true, type: 'label' },
    { key: 'declaredCurrency',       label: 'Currency',       sortable: true },
    { key: 'period',                 label: 'Period',         sortable: false },
    { key: 'status',                 label: 'Status',         sortable: true, type: 'status' },
    { key: 'aggregateLimitDisplay',  label: 'Aggregate limit',sortable: false },
  ];

  readonly statusOptions: SelectOption[] = [
    { value: '',         label: 'Any status' },
    { value: 'DRAFT',    label: 'Draft' },
    { value: 'ACTIVE',   label: 'Active' },
    { value: 'EXPIRED',  label: 'Expired' },
    { value: 'RENEWED',  label: 'Renewed' },
    { value: 'LAPSED',   label: 'Lapsed' },
    { value: 'COMMUTED', label: 'Commuted' },
  ];

  readonly typeOptions: SelectOption[] = [
    { value: '',                label: 'Any type' },
    { value: 'QUOTA_SHARE',     label: 'Quota Share' },
    { value: 'SURPLUS_SHARE',   label: 'Surplus Share' },
    { value: 'EXCESS_OF_LOSS',  label: 'Excess of Loss' },
    { value: 'STOP_LOSS',       label: 'Stop Loss' },
  ];

  get currencyOptions(): SelectOption[] {
    return [
      { value: '', label: 'Any currency' },
      ...this.currencies.map(c => ({ value: c, label: c })),
    ];
  }

  private allRows: TreatyRow[] = [];

  constructor(private svc: ReinsuranceService, private router: Router) {}

  ngOnInit(): void {
    this.fetchAll();
  }

  fetchAll(): void {
    this.loading = true;
    this.errorMessage = null;
    this.svc.listTreaties(0, 500, this.selectedStatus || undefined).subscribe({
      next: (resp) => {
        this.allRows = this.shape(resp.content);
        this.currencies = Array.from(new Set(this.allRows.map(r => r.declaredCurrency))).sort();
        this.applyClientFilters();
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Failed to load treaties';
        this.rows = [];
        this.allRows = [];
        this.loading = false;
      },
    });
  }

  onServerFilterChange(): void {
    // Status is server-side; the rest is client-side over the loaded page.
    this.fetchAll();
  }

  onClientFilterChange(): void {
    this.applyClientFilters();
  }

  onSearchInput(value: string): void {
    this.searchTerm = value ?? '';
    this.applyClientFilters();
  }

  clearFilters(): void {
    this.selectedStatus = '';
    this.selectedType = '';
    this.selectedCurrency = '';
    this.searchTerm = '';
    this.fetchAll();
  }

  onRowClick(row: TreatyRow): void {
    this.router.navigate(['/tenant/admin/reinsurance/treaties', row.id]);
  }

  private applyClientFilters(): void {
    const q = this.searchTerm.trim().toLowerCase();
    this.rows = this.allRows.filter(r => {
      if (this.selectedType && r.treatyType !== this.selectedType) return false;
      if (this.selectedCurrency && r.declaredCurrency !== this.selectedCurrency) return false;
      if (q) {
        const hay = `${r.treatyRef} ${r.renewalChain} ${r.producerRef ?? ''}`.toLowerCase();
        if (!hay.includes(q)) return false;
      }
      return true;
    });
  }

  /**
   * Compute the renewal-chain root treatyRef for each treaty so the table
   * carries the grouping context without needing a per-chain card.
   * Sort inception DESC so the most recent treaties surface first.
   */
  private shape(all: Treaty[]): TreatyRow[] {
    const byId = new Map(all.map(t => [t.id, t] as const));
    const rootFor = (t: Treaty): Treaty => {
      let cur = t;
      const seen = new Set<string>();
      while (cur.renewedFromTreatyId && byId.has(cur.renewedFromTreatyId) && !seen.has(cur.id)) {
        seen.add(cur.id);
        cur = byId.get(cur.renewedFromTreatyId)!;
      }
      return cur;
    };
    return all
      .map(t => ({
        ...t,
        renewalChain: rootFor(t).treatyRef,
        period: `${t.inceptionDate} to ${t.expiryDate}`,
        aggregateLimitDisplay: t.aggregateLimit != null
          ? `${t.aggregateLimit.toLocaleString()} ${t.aggregateLimitCurrency ?? ''}`.trim()
          : '',
      }))
      .sort((a, b) => b.inceptionDate.localeCompare(a.inceptionDate));
  }
}
