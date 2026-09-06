import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import {
  Adjustment,
  CommissionAdjustmentService,
} from '../../../../../core/services/commission-adjustment.service';

/**
 * Read-only detail view for a single commission correction. Shows the
 * lifecycle (create → approve → commit / void) as a timeline plus the
 * linked compensating commission_transaction after commit.
 */
@Component({
  selector: 'app-correction-detail',
  standalone: true,
  imports: [CommonModule, RouterLink],
  templateUrl: './correction-detail.component.html',
  styleUrl: './correction-detail.component.scss',
})
export class CorrectionDetailComponent implements OnInit {
  correction: Adjustment | null = null;
  loading = false;
  errorMessage: string | null = null;

  constructor(
    private route: ActivatedRoute,
    private svc: CommissionAdjustmentService,
  ) {}

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (!id) {
      this.errorMessage = 'Missing correction id';
      return;
    }
    this.loading = true;
    this.svc.get(id).subscribe({
      next: adj => { this.correction = adj; this.loading = false; },
      error: err => {
        this.errorMessage = err?.error?.detail || 'Failed to load correction.';
        this.loading = false;
      },
    });
  }
}
