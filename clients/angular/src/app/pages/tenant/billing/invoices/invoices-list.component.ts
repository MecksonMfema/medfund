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
import { PermissionService } from '../../../../core/security/permission.service';
import { DataTableComponent, TableAction, TableColumn } from '../../../../shared/components/data-table/data-table.component';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { ToastService } from '../../../../shared/components/toast/toast.service';
import { ConfirmService } from '../../../../shared/components/confirm-dialog/confirm.service';

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
    {
      // Permission gate is enforced via the `visible` predicate so
      // super admins (whose PermissionService.hasAny bypass returns true)
      // see the action even when their tenant role catalogue doesn't
      // explicitly grant billing:revoke_billing. The row-level gate
      // additionally hides it for any invoice outside the next-month
      // revoke window — same rule the backend enforces.
      label: 'Revoke statement',
      icon: 'x-circle',
      color: 'danger',
      requiresPermission: 'billing:revoke_billing',
      visible: (row: any) => this.canRevokeRow(row),
      handler: (row: any) => this.revokeRow(row),
    },
  ];

  private subs: Subscription[] = [];

  constructor(
    private contributions: ContributionsService,
    private tenantSvc: TenantService,
    private perms: PermissionService,
    private router: Router,
    private toast: ToastService,
    private confirmSvc: ConfirmService,
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
        this.toast.error(err?.error?.detail || 'Failed to load contribution statements');
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

  /**
   * Per-row revoke gate. Matches the backend's strict next-month window
   * (BillingService.revokeBilling) so the action is hidden for any
   * invoice the API would reject. Super admins still need the row to
   * fall inside the window — revoke is a destructive workflow, not a
   * privileged override.
   */
  private canRevokeRow(row: any): boolean {
    if (!this.perms.hasAny(['billing:revoke_billing'])) return false;
    if (!row?.periodStart) return false;
    return row.periodStart === this.firstOfNextMonthISO();
  }

  /**
   * Bulk-revoke gate for the page-header button. Same permission +
   * next-month-only window, but hides the button when nothing on the
   * current page is actually revocable — otherwise the operator would
   * just get a 422 on click. Lower-bound guard: if a revocable row
   * exists on a later page the button won't appear until the user
   * pages/filters to it.
   */
  get canBulkRevoke(): boolean {
    if (!this.perms.hasAny(['billing:revoke_billing'])) return false;
    const target = this.firstOfNextMonthISO();
    return this.rows.some(r => (r as any).periodStart === target);
  }

  /** Human label for the bulk-revoke button — reflects the active tab. */
  get bulkRevokeLabel(): string {
    const monthLabel = this.formatNextMonth();
    const scope = this.activeTab
      ? (LINE_LABELS[this.activeTab] ?? this.activeTab)
      : 'all lines';
    return `Revoke ${monthLabel} (${scope})`;
  }

  /**
   * Confirm-then-revoke every next-month statement matching the active
   * line tab. Wizard's post-failed-commit banner uses the same endpoint
   * — this button is the discoverable, always-on twin.
   */
  async bulkRevoke(): Promise<void> {
    const monthLabel = this.formatNextMonth();
    const scope = this.activeTab
      ? (LINE_LABELS[this.activeTab] ?? this.activeTab)
      : 'every insurance line';
    const ok = await this.confirmSvc.ask({
      title: `Revoke all ${monthLabel} statements (${scope})?`,
      message:
        `This deletes every contribution + statement issued for ${monthLabel} across ${scope}, ` +
        `reverses each running-balance debit, and removes the PDFs. You'll need to re-commit ` +
        `to regenerate. This cannot be undone.`,
      confirmLabel: 'Revoke entire period',
      cancelLabel: 'Cancel',
      danger: true,
    });
    if (!ok) return;
    this.contributions.revokeBilling({
      periodStart:   this.firstOfNextMonthISO(),
      periodEnd:     this.lastOfNextMonthISO(),
      insuranceLine: this.activeTab ?? undefined,
    }).subscribe({
      next: (resp) => {
        this.toast.success(
          `Revoked ${resp.invoicesDeleted} statement(s) and ${resp.contributionsDeleted} contribution(s).`
        );
        this.fetchPage();
      },
      error: (err) => {
        this.toast.error(err?.error?.detail || err?.error?.title || 'Bulk revoke failed');
      },
    });
  }

  private firstOfNextMonthISO(): string {
    const now = new Date();
    // Date constructor normalizes month overflow (Dec → Jan next year).
    const first = new Date(now.getFullYear(), now.getMonth() + 1, 1);
    return `${first.getFullYear().toString().padStart(4, '0')}-`
         + `${(first.getMonth() + 1).toString().padStart(2, '0')}-01`;
  }

  private lastOfNextMonthISO(): string {
    const now = new Date();
    // day 0 of "month after next" rolls back to the last day of next month.
    const last = new Date(now.getFullYear(), now.getMonth() + 2, 0);
    return `${last.getFullYear().toString().padStart(4, '0')}-`
         + `${(last.getMonth() + 1).toString().padStart(2, '0')}-`
         + `${last.getDate().toString().padStart(2, '0')}`;
  }

  private formatNextMonth(): string {
    const now = new Date();
    const next = new Date(now.getFullYear(), now.getMonth() + 1, 1);
    return next.toLocaleString(undefined, { month: 'long', year: 'numeric' });
  }

  /**
   * Confirm-then-revoke a single statement. Scopes strictly to the row's
   * invoice id so other statements for the same period are unaffected.
   * The wizard's bulk "revoke entire period" flow lives on the generate
   * page and stays untouched.
   */
  private async revokeRow(row: any): Promise<void> {
    const ok = await this.confirmSvc.ask({
      title: `Revoke statement ${row.invoiceNumber}?`,
      message:
        `This deletes only statement ${row.invoiceNumber} for ${row.periodStart} → ${row.periodEnd}, ` +
        `reverses its running-balance debits, and removes its PDF. Other statements for this ` +
        `period are unaffected.`,
      confirmLabel: 'Revoke this statement',
      cancelLabel: 'Keep statement',
      danger: true,
    });
    if (!ok) return;
    this.contributions.revokeStatement(row.id).subscribe({
      next: (resp) => {
        this.toast.success(
          `Revoked ${row.invoiceNumber}: ${resp.contributionsDeleted} contribution(s) reversed.`
        );
        this.fetchPage();
      },
      error: (err) => {
        this.toast.error(err?.error?.detail || err?.error?.title || 'Revoke failed');
      },
    });
  }
}
