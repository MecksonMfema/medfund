import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import {
  ContributionsService,
  InvoiceContributionRow,
  InvoiceListRow,
} from '../../../../core/services/contributions.service';
import { StatementResponse, StatementLine } from '../../../../core/services/statements.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { ToastService } from '../../../../shared/components/toast/toast.service';

interface SchemeGroup {
  schemeName: string;
  insuranceLine: string;
  rows: InvoiceContributionRow[];
  subtotal: number;
}

/**
 * Snapshot-backed statement detail for one invoice. Header opening +
 * closing balances are read from the invoice row (captured at commit
 * time). The chronological ledger comes from
 * /invoices/{id}/statement; the per-scheme member breakdown from
 * /invoices/{id}/contributions.
 */
@Component({
  selector: 'app-invoice-statement',
  standalone: true,
  imports: [CommonModule, RouterLink, IconComponent],
  templateUrl: './invoice-statement.component.html',
  styleUrl: './invoice-statement.component.scss',
})
export class InvoiceStatementComponent implements OnInit {
  invoiceId = '';
  loading = false;
  errorMessage: string | null = null;

  invoice: any | null = null;          // raw InvoiceResponse (carries snapshot fields)
  listRow: InvoiceListRow | null = null;
  statement: StatementResponse | null = null;
  contributions: InvoiceContributionRow[] = [];

  schemeGroups: SchemeGroup[] = [];
  expandedSchemes = new Set<string>();

  // Financial summary derived from statement lines.
  monthlyContributions = 0;
  totalPayments = 0;
  totalAdjustments = 0;

  constructor(
    private route: ActivatedRoute,
    private contribSvc: ContributionsService,
    private toast: ToastService,
  ) {}

  ngOnInit(): void {
    this.invoiceId = this.route.snapshot.paramMap.get('id') ?? '';
    if (!this.invoiceId) {
      this.errorMessage = 'Invoice id missing from URL';
      return;
    }
    this.load();
  }

  private load(): void {
    this.loading = true;
    forkJoin({
      invoice:       this.contribSvc.getInvoiceById(this.invoiceId),
      statement:     this.contribSvc.getInvoiceStatement(this.invoiceId),
      contributions: this.contribSvc.getInvoiceContributions(this.invoiceId),
    }).subscribe({
      next: ({ invoice, statement, contributions }) => {
        this.invoice = invoice;
        this.statement = statement;
        this.contributions = contributions ?? [];
        this.groupBySchemes();
        this.deriveFinancialTotals();
        this.loading = false;
      },
      error: (err) => {
        this.loading = false;
        this.errorMessage = err?.error?.detail || 'Failed to load invoice statement';
        this.toast.error(this.errorMessage ?? 'Failed');
      },
    });
  }

  private groupBySchemes(): void {
    const buckets = new Map<string, SchemeGroup>();
    for (const c of this.contributions) {
      const key = c.schemeName || '(no scheme)';
      let g = buckets.get(key);
      if (!g) {
        g = { schemeName: key, insuranceLine: c.insuranceLine, rows: [], subtotal: 0 };
        buckets.set(key, g);
      }
      g.rows.push(c);
      const amt = parseFloat(c.amount);
      if (Number.isFinite(amt)) g.subtotal += amt;
    }
    this.schemeGroups = Array.from(buckets.values());
    // Default to all collapsed for tidiness — operator clicks to expand.
    this.expandedSchemes.clear();
  }

  private deriveFinancialTotals(): void {
    this.monthlyContributions = 0;
    this.totalPayments = 0;
    this.totalAdjustments = 0;
    for (const line of (this.statement?.lines ?? [])) {
      const debit  = line.debit  ? parseFloat(line.debit)  : 0;
      const credit = line.credit ? parseFloat(line.credit) : 0;
      if (line.type === 'CONTRIBUTION') {
        this.monthlyContributions += debit;
      } else if (line.type === 'TRANSACTION') {
        // Credit = payment; debit = adjustment (debit note / loaded premium)
        if (credit > 0) this.totalPayments += credit;
        if (debit  > 0) this.totalAdjustments += debit;
      } else if (line.type === 'CONTRIBUTION_PAID') {
        this.totalPayments += credit;
      }
    }
  }

  toggleScheme(scheme: string): void {
    if (this.expandedSchemes.has(scheme)) this.expandedSchemes.delete(scheme);
    else this.expandedSchemes.add(scheme);
  }

  isExpanded(scheme: string): boolean {
    return this.expandedSchemes.has(scheme);
  }

  pdfUrl(): string { return this.contribSvc.getInvoicePdfUrl(this.invoiceId); }

  get isPdfReady(): boolean {
    // The single invoice GET doesn't carry pdfReady today, so we treat
    // the link as always available — clicking 404s if it hasn't been
    // rendered yet. Cheaper than a separate poll.
    return true;
  }

  get holderName(): string {
    return this.invoice?.groupId
        ? (this.statement?.header?.targetName ?? this.invoice?.groupId)
        : (this.statement?.header?.targetName ?? `Member ${this.invoice?.memberId ?? ''}`);
  }

  get holderType(): string {
    return this.invoice?.groupId ? 'GROUP' : 'INDIVIDUAL';
  }
}
