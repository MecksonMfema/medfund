import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import {
  Adjustment,
  CommissionAdjustmentService,
} from '../../../../../core/services/commission-adjustment.service';

/**
 * Phase 8 §B — read-only detail page. Shows the adjustment lifecycle
 * (create → approve → commit / void) as a timeline plus the linked
 * compensating commission_transaction after commit.
 */
@Component({
  selector: 'app-adjustment-detail',
  standalone: true,
  imports: [CommonModule, RouterLink],
  templateUrl: './adjustment-detail.component.html',
  styleUrl: './adjustments.component.scss',
})
export class AdjustmentDetailComponent implements OnInit {
  adjustment: Adjustment | null = null;
  loading = false;
  errorMessage: string | null = null;

  constructor(
    private route: ActivatedRoute,
    private svc: CommissionAdjustmentService,
  ) {}

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (!id) {
      this.errorMessage = 'Missing adjustment id';
      return;
    }
    this.loading = true;
    this.svc.get(id).subscribe({
      next: adj => { this.adjustment = adj; this.loading = false; },
      error: err => {
        this.errorMessage = err?.error?.detail || 'Failed to load adjustment.';
        this.loading = false;
      },
    });
  }
}
