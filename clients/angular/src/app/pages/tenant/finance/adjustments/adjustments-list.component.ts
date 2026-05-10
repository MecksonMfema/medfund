import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import {
  Adjustment,
  AdjustmentStatus,
  AdjustmentType,
  FinanceService,
} from '../../../../core/services/finance.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { CurrencyFormatPipe } from '../../../../shared/pipes/currency-format.pipe';
import { HumanizePipe } from '../../../../shared/pipes/humanize.pipe';

@Component({
  selector: 'app-adjustments-list',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent, SkeletonComponent, CurrencyFormatPipe, HumanizePipe],
  templateUrl: './adjustments-list.component.html',
  styleUrl: './adjustments-list.component.scss',
})
export class AdjustmentsListComponent implements OnInit {
  rows: Adjustment[] = [];
  filtered: Adjustment[] = [];
  loading = false;
  errorMessage: string | null = null;

  presetType: AdjustmentType | '' = '';
  pageTitle = 'Adjustments';
  pageDescription = 'Financial adjustments awaiting approval and application.';

  statusFilter: AdjustmentStatus = 'pending';
  typeFilter = '';
  searchTerm = '';

  constructor(
    private finance: FinanceService,
    private route: ActivatedRoute,
    private router: Router,
  ) {}

  ngOnInit(): void {
    const data = this.route.snapshot.data;
    if (data['presetType']) {
      this.presetType = data['presetType'];
      this.typeFilter = this.presetType;
    }
    if (data['title']) this.pageTitle = data['title'];
    if (data['description']) this.pageDescription = data['description'];

    this.refresh();
  }

  refresh(): void {
    this.loading = true;
    this.finance.getAdjustmentsByStatus(this.statusFilter).subscribe({
      next: (rows) => { this.rows = rows; this.applyFilter(); this.loading = false; },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load adjustments';
        this.loading = false;
      },
    });
  }

  onStatusChange(): void { this.refresh(); }
  onFilterChange(): void { this.applyFilter(); }

  open(adj: Adjustment): void {
    this.router.navigate(['/tenant/finance/adjustments', adj.id]);
  }

  private applyFilter(): void {
    let rows = this.rows;
    if (this.typeFilter) {
      rows = rows.filter(r => r.adjustmentType === this.typeFilter);
    }
    const q = this.searchTerm.trim().toLowerCase();
    if (q) {
      rows = rows.filter(r =>
        r.adjustmentNumber?.toLowerCase().includes(q) ||
        (r.reason && r.reason.toLowerCase().includes(q)),
      );
    }
    this.filtered = rows;
  }
}
