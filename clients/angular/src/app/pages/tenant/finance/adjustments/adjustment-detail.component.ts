import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router } from '@angular/router';
import {
  Adjustment,
  FinanceService,
} from '../../../../core/services/finance.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { CurrencyFormatPipe } from '../../../../shared/pipes/currency-format.pipe';
import { HumanizePipe } from '../../../../shared/pipes/humanize.pipe';

@Component({
  selector: 'app-adjustment-detail',
  standalone: true,
  imports: [CommonModule, IconComponent, SkeletonComponent, CurrencyFormatPipe, HumanizePipe],
  templateUrl: './adjustment-detail.component.html',
  styleUrl: './adjustment-detail.component.scss',
})
export class AdjustmentDetailComponent implements OnInit {
  adjustment: Adjustment | null = null;
  loading = false;
  busy = false;
  errorMessage: string | null = null;
  successMessage: string | null = null;

  constructor(
    private finance: FinanceService,
    private route: ActivatedRoute,
    private router: Router,
  ) {}

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (!id) {
      this.errorMessage = 'No adjustment id';
      return;
    }
    this.refresh(id);
  }

  refresh(id: string): void {
    this.loading = true;
    this.finance.getAdjustment(id).subscribe({
      next: (a) => { this.adjustment = a; this.loading = false; },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load adjustment';
        this.loading = false;
      },
    });
  }

  approve(): void {
    if (!this.adjustment) return;
    this.busy = true;
    this.finance.approveAdjustment(this.adjustment.id).subscribe({
      next: (a) => {
        this.adjustment = a;
        this.successMessage = `Adjustment ${a.adjustmentNumber} approved.`;
        this.busy = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to approve';
        this.busy = false;
      },
    });
  }

  apply(): void {
    if (!this.adjustment) return;
    if (!confirm(`Apply adjustment ${this.adjustment.adjustmentNumber}? This is final.`)) return;
    this.busy = true;
    this.finance.applyAdjustment(this.adjustment.id).subscribe({
      next: (a) => {
        this.adjustment = a;
        this.successMessage = `Adjustment ${a.adjustmentNumber} applied.`;
        this.busy = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to apply';
        this.busy = false;
      },
    });
  }

  back(): void {
    this.router.navigate(['/tenant/finance/adjustments']);
  }
}
