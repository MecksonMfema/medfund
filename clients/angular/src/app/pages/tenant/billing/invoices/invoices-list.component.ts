import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import {
  ContributionsService,
  InvoiceListFilter,
  InvoiceListRow,
  InvoicesPage,
} from '../../../../core/services/contributions.service';
import { TenantService } from '../../../../core/services/tenant.service';
import { DataTableComponent, TableAction, TableColumn } from '../../../../shared/components/data-table/data-table.component';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { ToastService } from '../../../../shared/components/toast/toast.service';

interface LineTab {
  /** API filter value (insurance_line code) or null for the "All" tab. */
  value: string | null;
  label: string;
}

const LINE_LABELS: Record<string, string> = {
  HEALTH:     'Health',
  VEHICLE:    'Motor',
  MOTOR:      'Motor',
  PROPERTY:   'Property',
  LIFE:       'Life',
  FUNERAL:    'Funeral',
  TRAVEL:     'Travel',
  DISABILITY: 'Disability',
  GROUP:      'Group',
};

/**
 * Operator-facing invoice listing — one row per invoice (group or
 * individual), names not UUIDs. Layout mirrors /tenant/billing/schemes:
 * page-header → insurance-line tab strip → DataTable with server-side
 * pagination + search + sort.
 */
@Component({
  selector: 'app-invoices-list',
  standalone: true,
  imports: [CommonModule, RouterLink, DataTableComponent, IconComponent],
  templateUrl: './invoices-list.component.html',
  styleUrl: './invoices-list.component.scss',
})
export class InvoicesListComponent implements OnInit, OnDestroy {
  rows: InvoiceListRow[] = [];

  // Server-side pagination + sort state. Page is 1-indexed in the UI
  // (matches the data-table's serverPage input) and 0-indexed in the API.
  page = 1;
  pageSize = 20;
  totalCount = 0;
  totalPages = 1;
  loading = false;
  searchTerm = '';
  sortKey: string = 'issuedAt';
  sortDirection: 'asc' | 'desc' = 'desc';

  /** Insurance-line tabs derived from the tenant's enabled lines.
   *  Always rendered (even single-line tenants see the strip) so the
   *  "separated by insurance line" semantics is visible — but tabs are
   *  reduced to ["All", "<the one line>"] in that case. */
  tabs: LineTab[] = [];
  activeTab: string | null = null;
  showTabs = false;

  columns: TableColumn[] = [
    { key: 'invoiceNumber',  label: 'Invoice #',     sortable: true },
    { key: 'issuedAt',       label: 'Issued',        sortable: true, type: 'date' },
    { key: 'holderName',     label: 'Holder',        sortable: true },
    { key: 'insuranceLines', label: 'Line(s)',       type: 'lineList' },
    { key: 'periodLabel',    label: 'Period' },
    { key: 'totalAmount',    label: 'Total',         sortable: true, type: 'currency' },
    { key: 'status',         label: 'Status',        type: 'status',  sortable: true },
    { key: 'pdfReady',       label: 'PDF',           type: 'pdfFlag' },
  ];

  actions: TableAction[] = [
    {
      label: 'View statement',
      icon: 'file-text',
      color: 'default',
      handler: (row: any) => this.router.navigate(['/tenant/billing/view', row.id]),
    },
    {
      label: 'Download PDF',
      icon: 'download',
      color: 'default',
      labelFor: (row: any) => row.pdfReady ? 'Download PDF' : 'PDF rendering…',
      handler: (row: any) => {
        if (!row.pdfReady) {
          this.toast.info('PDF is still being rendered. Try again in a moment.');
          return;
        }
        window.open(this.contributions.getInvoicePdfUrl(row.id), '_blank');
      },
    },
  ];

  private subs: Subscription[] = [];

  constructor(
    private contributions: ContributionsService,
    private tenantSvc: TenantService,
    private router: Router,
    private toast: ToastService,
  ) {}

  ngOnInit(): void {
    // React to tenant changes so a tenant switch refreshes the tabs.
    this.subs.push(
      this.tenantSvc.tenant$.subscribe(t => this.rebuildTabs(t?.insuranceLines ?? [])),
    );
    this.fetchPage();
  }

  ngOnDestroy(): void {
    this.subs.forEach(s => s.unsubscribe());
  }

  private rebuildTabs(lines: string[]): void {
    const available = (lines && lines.length > 0) ? lines : ['HEALTH'];
    this.tabs = [
      { value: null, label: 'All' },
      ...available.map(code => ({ value: code, label: LINE_LABELS[code] ?? code })),
    ];
    this.showTabs = true;
    if (this.activeTab && !available.includes(this.activeTab)) {
      this.activeTab = null;
      this.fetchPage();
    }
  }

  selectTab(value: string | null): void {
    if (this.activeTab === value) return;
    this.activeTab = value;
    this.page = 1;
    this.fetchPage();
  }

  fetchPage(): void {
    this.loading = true;
    const filter: InvoiceListFilter = {
      page: this.page - 1,
      size: this.pageSize,
      sortKey: this.sortKey,
      sortDirection: this.sortDirection,
      q: this.searchTerm || undefined,
      insuranceLine: this.activeTab || undefined,
    };
    this.contributions.listInvoices(filter).subscribe({
      next: (resp: InvoicesPage) => {
        // Decorate rows so the table's generic cells render cleanly without
        // bespoke templates: a single periodLabel string, money column carries
        // amount + currency together.
        this.rows = (resp.content ?? []).map(r => ({
          ...r,
          periodLabel: `${r.periodStart} → ${r.periodEnd}`,
        }) as any);
        this.totalCount = resp.total ?? 0;
        this.totalPages = resp.totalPages ?? 1;
        this.page = (resp.page ?? 0) + 1;
        this.loading = false;
      },
      error: (err) => {
        this.loading = false;
        this.toast.error(err?.error?.detail || 'Failed to load invoices');
      },
    });
  }

  onSort(e: { key: string; direction: 'asc' | 'desc' }): void {
    this.sortKey = e.key;
    this.sortDirection = e.direction;
    this.fetchPage();
  }

  onSearch(term: string): void {
    this.searchTerm = term;
    this.page = 1;
    this.fetchPage();
  }

  onPageChange(p: number): void {
    this.page = p;
    this.fetchPage();
  }

  rowClick(row: any): void {
    if (row?.id) this.router.navigate(['/tenant/billing/view', row.id]);
  }
}
