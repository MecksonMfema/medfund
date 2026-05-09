import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import {
  ClaimsConfigService,
  RejectionReason,
} from '../../../../core/services/claims-config.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { HumanizePipe } from '../../../../shared/pipes/humanize.pipe';

@Component({
  selector: 'app-rejection-reasons-list',
  standalone: true,
  imports: [CommonModule, RouterLink, IconComponent, SkeletonComponent, HumanizePipe],
  templateUrl: './rejection-reasons-list.component.html',
  styleUrl: './rejection-reasons-list.component.scss',
})
export class RejectionReasonsListComponent implements OnInit {
  rows: RejectionReason[] = [];
  loading = false;
  errorMessage: string | null = null;
  pendingId: string | null = null;

  constructor(private config: ClaimsConfigService, private router: Router) {}

  ngOnInit(): void { this.refresh(); }

  refresh(): void {
    this.loading = true;
    this.config.listRejectionReasons(false).subscribe({
      next: (rows) => { this.rows = rows; this.loading = false; },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load rejection reasons';
        this.loading = false;
      },
    });
  }

  edit(r: RejectionReason): void {
    this.router.navigate(['/tenant/claims/rejection-reasons', r.id, 'edit']);
  }

  toggleActive(r: RejectionReason): void {
    const next = !r.isActive;
    if (!confirm(`${next ? 'Activate' : 'Deactivate'} ${r.code}?`)) return;
    this.pendingId = r.id;
    this.config.updateRejectionReason(r.id, {
      code: r.code,
      description: r.description,
      category: r.category,
      isActive: next,
    }).subscribe({
      next: (updated) => {
        this.rows = this.rows.map(x => x.id === updated.id ? updated : x);
        this.pendingId = null;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Update failed';
        this.pendingId = null;
      },
    });
  }

  remove(r: RejectionReason): void {
    if (!confirm(`Delete ${r.code} permanently? Consider deactivating instead.`)) return;
    this.pendingId = r.id;
    this.config.deleteRejectionReason(r.id).subscribe({
      next: () => {
        this.rows = this.rows.filter(x => x.id !== r.id);
        this.pendingId = null;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Delete failed';
        this.pendingId = null;
      },
    });
  }
}
