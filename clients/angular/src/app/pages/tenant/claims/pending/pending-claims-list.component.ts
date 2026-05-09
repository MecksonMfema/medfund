import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import {
  Claim,
  ClaimsService,
  ClaimStatus,
} from '../../../../core/services/claims.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { CurrencyFormatPipe } from '../../../../shared/pipes/currency-format.pipe';
import { HumanizePipe } from '../../../../shared/pipes/humanize.pipe';

@Component({
  selector: 'app-pending-claims-list',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent, SkeletonComponent, CurrencyFormatPipe, HumanizePipe],
  templateUrl: './pending-claims-list.component.html',
  styleUrl: './pending-claims-list.component.scss',
})
export class PendingClaimsListComponent implements OnInit {
  rows: Claim[] = [];
  filtered: Claim[] = [];
  loading = false;
  errorMessage: string | null = null;

  // Preset (route-driven) — locks the status filter when set so the URL
  // identity is preserved. Sub-routes (accepted, rejected, etc.) seed this
  // via route data.
  presetStatus = '';
  pageTitle = 'Pending claims';
  pageDescription = 'Claims queued for adjudication. Click any row to open the detail view and decide.';

  // Filter state
  statusFilter = '';
  searchTerm = '';

  constructor(private claims: ClaimsService, private route: ActivatedRoute, private router: Router) {}

  ngOnInit(): void {
    const data = this.route.snapshot.data;
    if (data['presetStatus']) {
      this.presetStatus = data['presetStatus'];
      this.statusFilter = this.presetStatus;
    } else {
      this.statusFilter = 'VERIFIED'; // default to "ready for adjudication"
    }
    if (data['title']) this.pageTitle = data['title'];
    if (data['description']) this.pageDescription = data['description'];

    this.refresh();
  }

  refresh(): void {
    this.loading = true;
    const stream = this.statusFilter
      ? this.claims.getByStatus(this.statusFilter)
      : this.claims.list();
    stream.subscribe({
      next: (rows) => { this.rows = rows; this.applyFilter(); this.loading = false; },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load claims';
        this.loading = false;
      },
    });
  }

  onStatusChange(): void {
    this.refresh();
  }

  onSearchChange(): void {
    this.applyFilter();
  }

  open(claim: Claim): void {
    this.router.navigate(['/tenant/claims', claim.id]);
  }

  private applyFilter(): void {
    const q = this.searchTerm.trim().toLowerCase();
    if (!q) {
      this.filtered = this.rows;
      return;
    }
    this.filtered = this.rows.filter(c =>
      c.claimNumber?.toLowerCase().includes(q) ||
      (c.notes && c.notes.toLowerCase().includes(q)),
    );
  }
}
