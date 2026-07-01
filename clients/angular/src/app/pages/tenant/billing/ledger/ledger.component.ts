import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subject, debounceTime, distinctUntilChanged, of, switchMap } from 'rxjs';
import { GroupsService, Group } from '../../../../core/services/groups.service';
import { MembersService, Member } from '../../../../core/services/members.service';
import {
  StatementResponse,
  StatementsService,
  StatementTargetType,
} from '../../../../core/services/statements.service';
import { TenantService } from '../../../../core/services/tenant.service';
import { CurrencyService, TenantCurrencyConfig } from '../../../../core/services/currency.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { ToastService } from '../../../../shared/components/toast/toast.service';
import { CurrencyFormatPipe } from '../../../../shared/pipes/currency-format.pipe';
import { HumanizePipe } from '../../../../shared/pipes/humanize.pipe';

interface TargetOption {
  id: string;
  label: string;
  sublabel?: string;
}

/**
 * Financial ledger for a member or a group. Layout mirrors
 * /tenant/billing/schemes and /tenant/billing/view:
 *   1. page-header banner (title + sub + export actions on the right)
 *   2. tab strip (Individual / Group) — filters the target picker
 *   3. single-line filter bar (search + from + to + currency + Search)
 *   4. results below — summary cards + Date / Description / Reference / Debit / Credit / Balance table
 *
 * Reuses the existing /statements API on contributions-service — this is a
 * pure UI rebrand: same wire shape, same Excel export endpoint. The old
 * StatementsComponent stays only for the invoice-detail per-invoice view.
 */
@Component({
  selector: 'app-ledger',
  standalone: true,
  imports: [
    CommonModule, FormsModule,
    IconComponent, SelectComponent, SkeletonComponent, CurrencyFormatPipe, HumanizePipe,
  ],
  templateUrl: './ledger.component.html',
  styleUrl: './ledger.component.scss',
})
export class LedgerComponent implements OnInit {
  // ── Tab state (Individual / Group) ─────────────────────────────────────
  membershipModel: 'INDIVIDUAL_ONLY' | 'GROUP_ONLY' | 'BOTH' = 'BOTH';
  targetType: StatementTargetType = 'GROUP';

  // ── Filter bar state ───────────────────────────────────────────────────
  targetQuery = '';
  targetMatches: TargetOption[] = [];
  targetSearching = false;
  selectedTarget: TargetOption | null = null;
  showMatches = false;

  periodStart = '';
  periodEnd = '';
  currencyCode = '';
  currencies: TenantCurrencyConfig[] = [];

  // ── Result state ───────────────────────────────────────────────────────
  ledger: StatementResponse | null = null;
  loading = false;
  errorMessage: string | null = null;

  private query$ = new Subject<string>();

  get currencyOptions(): SelectOption[] {
    return [
      { value: '', label: 'Auto' },
      ...this.currencies.map(c => ({
        value: c.currencyCode,
        label: `${c.currencyCode}${c.isDefault ? ' (default)' : ''}`,
      })),
    ];
  }

  /** Only render the tab strip when the tenant has both models enabled. */
  get showTabs(): boolean {
    return this.membershipModel === 'BOTH';
  }

  constructor(
    private tenantService: TenantService,
    private currencyService: CurrencyService,
    private groupsService: GroupsService,
    private membersService: MembersService,
    private statementsService: StatementsService,
    private toast: ToastService,
  ) {}

  ngOnInit(): void {
    const tenant = this.tenantService.getTenant();
    if (!tenant) {
      this.errorMessage = 'No active tenant context';
      return;
    }
    this.membershipModel = tenant.membershipModel ?? 'BOTH';
    if (this.membershipModel === 'INDIVIDUAL_ONLY') this.targetType = 'MEMBER';
    else if (this.membershipModel === 'GROUP_ONLY') this.targetType = 'GROUP';

    // Default window — first-of-current-month through today so the
    // most common lookup ("show me this member's arrears for the month")
    // is one click away.
    const today = new Date();
    const firstOfMonth = new Date(today.getFullYear(), today.getMonth(), 1);
    this.periodStart = firstOfMonth.toISOString().slice(0, 10);
    this.periodEnd = today.toISOString().slice(0, 10);

    this.currencyService.listForTenant(tenant.id).subscribe({
      next: (rows) => {
        this.currencies = rows.filter(c => c.isActive);
        const def = this.currencies.find(c => c.isDefault);
        if (!this.currencyCode && def) this.currencyCode = def.currencyCode;
      },
      error: () => {},
    });

    this.query$
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        switchMap((q) => {
          const trimmed = q.trim();
          if (!trimmed) {
            this.targetSearching = false;
            return of<TargetOption[]>([]);
          }
          this.targetSearching = true;
          return this.targetType === 'GROUP'
            ? this.groupsService.search(trimmed).pipe(
                switchMap((rows: Group[]) => of<TargetOption[]>(
                  rows.map(g => ({ id: g.id, label: g.name, sublabel: g.registrationNumber || undefined }))
                ))
              )
            : this.membersService.searchByName(trimmed).pipe(
                switchMap((rows: Member[]) => of<TargetOption[]>(
                  rows.map(m => ({
                    id: m.id,
                    label: `${m.firstName} ${m.lastName}`.trim(),
                    sublabel: m.memberNumber,
                  }))
                ))
              );
        }),
      )
      .subscribe({
        next: (matches) => { this.targetMatches = matches; this.targetSearching = false; },
        error: () => { this.targetMatches = []; this.targetSearching = false; },
      });
  }

  // ── Tab handlers ─────────────────────────────────────────────────────
  selectTab(t: StatementTargetType): void {
    if (this.targetType === t) return;
    this.targetType = t;
    this.selectedTarget = null;
    this.targetQuery = '';
    this.targetMatches = [];
    this.ledger = null;
  }

  // ── Target picker handlers ────────────────────────────────────────────
  onTargetQueryChange(): void {
    this.showMatches = true;
    if (this.selectedTarget && this.targetQuery !== this.selectedTarget.label) {
      this.selectedTarget = null;
    }
    this.query$.next(this.targetQuery);
  }

  pickTarget(t: TargetOption): void {
    this.selectedTarget = t;
    this.targetQuery = t.label;
    this.showMatches = false;
    this.targetMatches = [];
  }

  clearTarget(): void {
    this.selectedTarget = null;
    this.targetQuery = '';
    this.ledger = null;
  }

  // Hide the suggestion dropdown when the operator clicks anywhere else.
  onTargetBlur(): void {
    setTimeout(() => { this.showMatches = false; }, 120);
  }

  // ── Submit ────────────────────────────────────────────────────────────
  search(): void {
    if (!this.selectedTarget) {
      this.toast.warning('Pick a ' + (this.targetType === 'GROUP' ? 'group' : 'member') + ' first');
      return;
    }
    if (!this.periodStart || !this.periodEnd) {
      this.toast.warning('Set both dates');
      return;
    }
    if (this.periodEnd < this.periodStart) {
      this.toast.warning('End date must be on or after start date');
      return;
    }
    this.loading = true;
    this.errorMessage = null;
    this.ledger = null;
    this.statementsService.generate({
      targetType: this.targetType,
      targetId: this.selectedTarget.id,
      periodStart: this.periodStart,
      periodEnd: this.periodEnd,
      currency: this.currencyCode || undefined,
    }).subscribe({
      next: (resp) => {
        this.ledger = resp;
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Failed to load ledger';
        this.loading = false;
      },
    });
  }

  // ── Exports ───────────────────────────────────────────────────────────
  print(): void {
    window.print();
  }

  /**
   * Client-side PDF fallback. The Excel export is the recommended path
   * (better for downstream Excel/CSV workflows) but we keep PDF for
   * operators who need a print-ready copy without going through Excel.
   */
  async exportPdf(): Promise<void> {
    if (!this.ledger) return;
    const [{ default: jsPDF }, autoTableModule] = await Promise.all([
      import('jspdf'),
      import('jspdf-autotable'),
    ]);
    const autoTable = (autoTableModule as { default?: unknown }).default ?? autoTableModule;
    const h = this.ledger.header;
    const doc = new jsPDF({ unit: 'pt', format: 'a4' });
    const margin = 36;
    let y = margin;

    doc.setFontSize(16).setFont('helvetica', 'bold');
    doc.text('Ledger', margin, y);
    y += 22;

    doc.setFontSize(11).setFont('helvetica', 'normal');
    doc.text(`${h.targetType.charAt(0) + h.targetType.slice(1).toLowerCase()}: ${h.targetName ?? h.targetId}`, margin, y);
    y += 14;
    if (h.targetCode) { doc.text(`Code: ${h.targetCode}`, margin, y); y += 14; }
    doc.text(`Period: ${h.periodStart} → ${h.periodEnd}    Currency: ${h.currencyCode}`, margin, y);
    y += 14;
    doc.text(`Opening: ${h.openingBalance}    Closing: ${h.closingBalance}`, margin, y);
    y += 14;
    doc.text(`Charges: ${h.totalCharges}    Payments: ${h.totalPayments}`, margin, y);
    y += 14;

    const body = this.ledger.lines.map(l => [
      l.date.slice(0, 10),
      l.description,
      l.reference ?? '—',
      l.debit ?? '',
      l.credit ?? '',
      l.runningBalance,
    ]);

    (autoTable as (doc: unknown, opts: Record<string, unknown>) => void)(doc, {
      startY: y + 8,
      head: [['Date', 'Description', 'Reference', 'Debit', 'Credit', 'Balance']],
      body,
      styles: { fontSize: 9, cellPadding: 4 },
      headStyles: { fillColor: [0, 119, 182], textColor: 255, fontStyle: 'bold' },
      columnStyles: {
        3: { halign: 'right' },
        4: { halign: 'right' },
        5: { halign: 'right', fontStyle: 'bold' },
      },
      margin: { left: margin, right: margin },
    });

    const filename = this.exportFilename('pdf');
    doc.save(filename);
  }

  exportExcel(): void {
    if (!this.ledger) return;
    this.statementsService.exportExcel({
      targetType: this.targetType,
      targetId: this.ledger.header.targetId,
      periodStart: this.ledger.header.periodStart,
      periodEnd: this.ledger.header.periodEnd,
      currency: this.ledger.header.currencyCode,
    }).subscribe({
      next: (blob) => {
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = this.exportFilename('xlsx');
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);
      },
      error: (err) => {
        this.toast.error(err?.error?.detail || 'Failed to export Excel');
      },
    });
  }

  private exportFilename(ext: 'pdf' | 'xlsx'): string {
    const h = this.ledger!.header;
    const slug = (h.targetName || h.targetId).replace(/[^a-z0-9]+/gi, '-').toLowerCase();
    return `ledger-${slug}-${h.periodStart}-${h.periodEnd}.${ext}`;
  }
}
