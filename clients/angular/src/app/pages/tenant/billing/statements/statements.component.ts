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
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { CurrencyFormatPipe } from '../../../../shared/pipes/currency-format.pipe';
import { HumanizePipe } from '../../../../shared/pipes/humanize.pipe';

interface TargetOption {
  id: string;
  label: string;
  sublabel?: string;
}

@Component({
  selector: 'app-statements',
  standalone: true,
  imports: [
    CommonModule, FormsModule,
    IconComponent, SkeletonComponent, CurrencyFormatPipe, HumanizePipe,
  ],
  templateUrl: './statements.component.html',
  styleUrl: './statements.component.scss',
})
export class StatementsComponent implements OnInit {
  // ── Filter state ───────────────────────────────────────────────────────
  membershipModel: 'INDIVIDUAL_ONLY' | 'GROUP_ONLY' | 'BOTH' = 'BOTH';
  targetType: StatementTargetType = 'GROUP';
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
  statement: StatementResponse | null = null;
  loading = false;
  errorMessage: string | null = null;

  private query$ = new Subject<string>();

  constructor(
    private tenantService: TenantService,
    private currencyService: CurrencyService,
    private groupsService: GroupsService,
    private membersService: MembersService,
    private statementsService: StatementsService,
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

    // Default period: first day of current month → today
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
        debounceTime(350),
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

  onTargetTypeChange(): void {
    this.clearTarget();
    this.targetQuery = '';
    this.targetMatches = [];
  }

  onTargetQueryChange(): void {
    this.showMatches = true;
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
    this.statement = null;
  }

  generate(): void {
    if (!this.selectedTarget) {
      this.errorMessage = 'Pick a target first';
      return;
    }
    if (!this.periodStart || !this.periodEnd) {
      this.errorMessage = 'Period start and end are required';
      return;
    }
    if (this.periodEnd < this.periodStart) {
      this.errorMessage = 'Period end must not be before period start';
      return;
    }
    this.loading = true;
    this.errorMessage = null;
    this.statement = null;
    this.statementsService.generate({
      targetType: this.targetType,
      targetId: this.selectedTarget.id,
      periodStart: this.periodStart,
      periodEnd: this.periodEnd,
      currency: this.currencyCode || undefined,
    }).subscribe({
      next: (resp) => {
        this.statement = resp;
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Failed to generate statement';
        this.loading = false;
      },
    });
  }

  editFilters(): void {
    this.statement = null;
  }

  print(): void {
    window.print();
  }

  async exportPdf(): Promise<void> {
    if (!this.statement) return;
    const [{ default: jsPDF }, autoTableModule] = await Promise.all([
      import('jspdf'),
      import('jspdf-autotable'),
    ]);
    const autoTable = (autoTableModule as { default?: unknown }).default ?? autoTableModule;
    const h = this.statement.header;
    const doc = new jsPDF({ unit: 'pt', format: 'a4' });
    const margin = 36;
    let y = margin;

    doc.setFontSize(16).setFont('helvetica', 'bold');
    doc.text('Contribution Statement', margin, y);
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

    const body = this.statement.lines.map(l => [
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

    const filename = `statement-${(h.targetName || h.targetId).replace(/[^a-z0-9]+/gi, '-').toLowerCase()}-${h.periodStart}-${h.periodEnd}.pdf`;
    doc.save(filename);
  }

  async exportExcel(): Promise<void> {
    if (!this.statement) return;
    this.statementsService.exportExcel({
      targetType: this.targetType,
      targetId: this.statement.header.targetId,
      periodStart: this.statement.header.periodStart,
      periodEnd: this.statement.header.periodEnd,
      currency: this.statement.header.currencyCode,
    }).subscribe({
      next: (blob) => {
        const h = this.statement!.header;
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `statement-${(h.targetName || h.targetId).replace(/[^a-z0-9]+/gi, '-').toLowerCase()}-${h.periodStart}-${h.periodEnd}.xlsx`;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to export Excel';
      },
    });
  }
}
