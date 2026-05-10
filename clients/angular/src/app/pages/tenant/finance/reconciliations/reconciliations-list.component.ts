import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import {
  BankReconciliation,
  FinanceService,
  ReconciliationStatus,
} from '../../../../core/services/finance.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { CurrencyFormatPipe } from '../../../../shared/pipes/currency-format.pipe';
import { HumanizePipe } from '../../../../shared/pipes/humanize.pipe';

@Component({
  selector: 'app-reconciliations-list',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent, SkeletonComponent, CurrencyFormatPipe, HumanizePipe],
  templateUrl: './reconciliations-list.component.html',
  styleUrl: './reconciliations-list.component.scss',
})
export class ReconciliationsListComponent implements OnInit {
  rows: BankReconciliation[] = [];
  filtered: BankReconciliation[] = [];
  loading = false;
  busyId: string | null = null;
  errorMessage: string | null = null;
  successMessage: string | null = null;

  statusFilter: ReconciliationStatus | '' = '';
  searchTerm = '';

  constructor(private finance: FinanceService, private router: Router) {}

  ngOnInit(): void {
    this.refresh();
  }

  refresh(): void {
    this.loading = true;
    const stream = this.statusFilter
      ? this.finance.getReconciliationsByStatus(this.statusFilter)
      : this.finance.listReconciliations();
    stream.subscribe({
      next: (rows) => { this.rows = rows; this.applyFilter(); this.loading = false; },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load reconciliations';
        this.loading = false;
      },
    });
  }

  onStatusChange(): void { this.refresh(); }
  onFilterChange(): void { this.applyFilter(); }

  match(row: BankReconciliation, event: Event): void {
    event.stopPropagation();
    if (!confirm(`Mark reconciliation ${row.referenceNumber} as matched?`)) return;
    this.busyId = row.id;
    this.finance.matchReconciliation(row.id).subscribe({
      next: (updated) => {
        const idx = this.rows.findIndex(r => r.id === updated.id);
        if (idx >= 0) this.rows[idx] = updated;
        this.applyFilter();
        this.successMessage = `Reconciliation ${updated.referenceNumber} matched.`;
        this.busyId = null;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to match';
        this.busyId = null;
      },
    });
  }

  private applyFilter(): void {
    const q = this.searchTerm.trim().toLowerCase();
    this.filtered = q
      ? this.rows.filter(r =>
          r.referenceNumber?.toLowerCase().includes(q) ||
          (r.notes && r.notes.toLowerCase().includes(q)),
        )
      : this.rows;
  }
}
