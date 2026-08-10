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
 * jsPDF's built-in Helvetica ships only the base 128 ASCII glyphs — no
 * arrows, no en/em dashes, no smart quotes. Passing "→" through
 * doc.text() silently renders as "!'" (or similar gibberish depending
 * on the font-encoding fallback). Rather than embed a full Unicode
 * font, we sanitise the common offenders to ASCII substitutes: → to
 * "->", en/em dashes to "-", curly quotes to straight quotes.
 * Applied uniformly to every string that hits jsPDF.
 */
function asciiSafe(s: string): string {
  if (!s) return s;
  return s
    .replace(/[→⟶➔]/g, '->')  // arrows
    .replace(/[–—]/g, '-')          // en/em dashes
    .replace(/[‘’]/g, "'")          // curly single quotes
    .replace(/[“”]/g, '"');         // curly double quotes
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
   *
   * jsPDF ships only the standard PDF 14 fonts (Helvetica / Times /
   * Courier), none of which include U+2192 (→) or other multibyte
   * glyphs — an arrow silently renders as gibberish ("!'"). Every
   * string we hand to doc.text() / autoTable is therefore sanitised
   * with {@link asciiSafe} to swap arrows for "->".
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
    const pageWidth = doc.internal.pageSize.getWidth();
    const contentWidth = pageWidth - margin * 2;
    let y = margin;

    // ── Title with a thin rule underneath (visual anchor) ─────────
    doc.setFontSize(20).setFont('helvetica', 'bold');
    doc.text('Ledger', margin, y);
    y += 10;
    doc.setDrawColor(220);
    doc.setLineWidth(0.5);
    doc.line(margin, y, pageWidth - margin, y);
    y += 18;

    // ── Two-column info block ─────────────────────────────────────
    //
    // Left column: identifying details (holder, code).
    // Right column: financial snapshot (period, currency, opening,
    // closing). Labels in muted small caps; values in normal weight
    // (balance values bold). Uses baseline math instead of tables so
    // the fields stay tightly packed without autoTable's row padding.
    const colWidth = contentWidth / 2;
    const leftX = margin;
    const rightX = margin + colWidth + 12;
    const rightValX = rightX + 92; // fixed offset for values → right-column labels line up
    const labelColor: [number, number, number] = [110, 122, 138];
    const bodyColor: [number, number, number] = [30, 34, 44];
    const labelFontSize = 8;
    const valueFontSize = 11;
    const lineHeight = 16;

    const holderLabel = h.targetType.charAt(0) + h.targetType.slice(1).toLowerCase();
    const holderValue = h.targetName ?? h.targetId;

    // Left column
    let ly = y;
    ly = this.writePdfField(doc, leftX, ly, `${holderLabel.toUpperCase()}`, holderValue,
                            labelColor, bodyColor, labelFontSize, valueFontSize, lineHeight);
    if (h.targetCode) {
      ly = this.writePdfField(doc, leftX, ly, 'CODE', h.targetCode,
                              labelColor, bodyColor, labelFontSize, valueFontSize, lineHeight);
    }

    // Right column
    let ry = y;
    ry = this.writePdfField(doc, rightX, ry, 'STATEMENT PERIOD',
                            asciiSafe(`${h.periodStart} to ${h.periodEnd}`),
                            labelColor, bodyColor, labelFontSize, valueFontSize, lineHeight);
    ry = this.writePdfField(doc, rightX, ry, 'CURRENCY', h.currencyCode,
                            labelColor, bodyColor, labelFontSize, valueFontSize, lineHeight);
    ry = this.writePdfField(doc, rightX, ry, 'OPENING BALANCE',
                            `${h.currencyCode} ${this.money(h.openingBalance)}`,
                            labelColor, bodyColor, labelFontSize, valueFontSize, lineHeight, true);
    ry = this.writePdfField(doc, rightX, ry, 'CLOSING BALANCE',
                            `${h.currencyCode} ${this.money(h.closingBalance)}`,
                            labelColor, bodyColor, labelFontSize, valueFontSize, lineHeight, true);

    y = Math.max(ly, ry) + 8;

    // ── Ledger table ──────────────────────────────────────────────
    //
    // Every string sanitised so a stray arrow in a description
    // (e.g. "Contribution 2026-08-01 → 2026-08-31" from the backend)
    // doesn't render as "!'" in the PDF.
    const currency = h.currencyCode;
    const bookendType = new Set(['OPENING_BALANCE', 'CLOSING_BALANCE']);
    const bookendRowIndices = new Set<number>();
    const body = this.ledger.lines.map((l, idx) => {
      if (bookendType.has(l.type)) bookendRowIndices.add(idx);
      const suffix = l.type === 'OPENING_BALANCE' ? ' (B/F)'
                   : l.type === 'CLOSING_BALANCE' ? ' (C/F)'
                   : '';
      return [
        l.date.slice(0, 10),
        asciiSafe(l.description + suffix),
        asciiSafe(l.reference ?? '—'),
        l.debit  ? `${currency} ${this.money(l.debit)}`  : '',
        l.credit ? `${currency} ${this.money(l.credit)}` : '',
        `${currency} ${this.money(l.runningBalance)}`,
      ];
    });

    (autoTable as (doc: unknown, opts: Record<string, unknown>) => void)(doc, {
      startY: y,
      head: [['Date', 'Description', 'Reference', 'Debit', 'Credit', 'Balance']],
      body,
      styles: { fontSize: 9, cellPadding: 4, textColor: bodyColor as unknown as number[] },
      headStyles: { fillColor: [15, 25, 40], textColor: 255, fontStyle: 'bold', fontSize: 9 },
      alternateRowStyles: { fillColor: [248, 250, 253] },
      columnStyles: {
        3: { halign: 'right' },
        4: { halign: 'right' },
        5: { halign: 'right', fontStyle: 'bold' },
      },
      margin: { left: margin, right: margin },
      didParseCell: (data: { section: string; row: { index: number }; cell: { styles: Record<string, unknown> } }) => {
        // Highlight bookend rows so they read as ledger boundaries.
        if (data.section === 'body' && bookendRowIndices.has(data.row.index)) {
          data.cell.styles['fillColor'] = [235, 242, 251];
          data.cell.styles['fontStyle'] = 'bold';
          data.cell.styles['lineWidth'] = 0.5;
          data.cell.styles['lineColor'] = [180, 200, 220];
        }
      },
    });

    // ── Compact totals footer ─────────────────────────────────────
    //
    // The autoTable plugin stashes the drawn table's finalY on the doc
    // (or on the plugin instance depending on the version). Read it
    // defensively.
    const finalY = ((doc as unknown as { lastAutoTable?: { finalY?: number } }).lastAutoTable?.finalY)
                   ?? y + 200;
    let fy = finalY + 24;
    doc.setFontSize(9).setTextColor(...labelColor).setFont('helvetica', 'normal');
    doc.text('Contributions this period', margin, fy);
    doc.setTextColor(...bodyColor).setFont('helvetica', 'normal');
    doc.text(`${currency} ${this.money(h.totalCharges)}`, pageWidth - margin, fy, { align: 'right' });
    fy += 14;
    doc.setTextColor(...labelColor);
    doc.text('Payments received', margin, fy);
    doc.setTextColor(...bodyColor);
    doc.text(`- ${currency} ${this.money(h.totalPayments)}`, pageWidth - margin, fy, { align: 'right' });
    fy += 8;
    doc.setDrawColor(30).setLineWidth(1);
    doc.line(pageWidth - margin - 140, fy, pageWidth - margin, fy);
    fy += 14;
    doc.setFontSize(11).setFont('helvetica', 'bold').setTextColor(...bodyColor);
    doc.text('Amount Due', margin, fy);
    doc.text(`${currency} ${this.money(h.closingBalance)}`, pageWidth - margin, fy, { align: 'right' });

    const filename = this.exportFilename('pdf');
    doc.save(filename);
  }

  /**
   * Draw a label-then-value field into the PDF at (x, y). Returns the
   * new y after advancing by lineHeight × 2 (label + value line). Used
   * by both info columns in the ledger export header so alignment
   * stays consistent.
   */
  private writePdfField(
    doc: import('jspdf').jsPDF,
    x: number,
    y: number,
    label: string,
    value: string,
    labelColor: [number, number, number],
    bodyColor: [number, number, number],
    labelFontSize: number,
    valueFontSize: number,
    lineHeight: number,
    boldValue = false,
  ): number {
    doc.setFontSize(labelFontSize).setFont('helvetica', 'normal').setTextColor(...labelColor);
    doc.text(label, x, y);
    doc.setFontSize(valueFontSize).setFont('helvetica', boldValue ? 'bold' : 'normal').setTextColor(...bodyColor);
    doc.text(value, x, y + lineHeight - 4);
    return y + lineHeight + 8;
  }

  /**
   * Format a money value to 2 decimal places for the PDF. Handles the
   * common BigDecimal.toString() shapes from the backend (raw "115" or
   * scaled "115.0000") without pulling in a formatting library.
   */
  private money(value: string | number | null | undefined): string {
    if (value === null || value === undefined || value === '') return '0.00';
    const n = typeof value === 'number' ? value : parseFloat(value);
    if (Number.isNaN(n)) return '0.00';
    return n.toFixed(2);
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
