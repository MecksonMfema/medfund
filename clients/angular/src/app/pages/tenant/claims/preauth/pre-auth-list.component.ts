import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import {
  PreAuthorization,
  PreAuthService,
} from '../../../../core/services/pre-auth.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { CurrencyFormatPipe } from '../../../../shared/pipes/currency-format.pipe';
import { HumanizePipe } from '../../../../shared/pipes/humanize.pipe';

@Component({
  selector: 'app-pre-auth-list',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent, SkeletonComponent, CurrencyFormatPipe, HumanizePipe],
  templateUrl: './pre-auth-list.component.html',
  styleUrl: './pre-auth-list.component.scss',
})
export class PreAuthListComponent implements OnInit {
  rows: PreAuthorization[] = [];
  filtered: PreAuthorization[] = [];
  loading = false;
  errorMessage: string | null = null;
  statusFilter = '';

  constructor(private service: PreAuthService, private router: Router) {}

  ngOnInit(): void { this.refresh(); }

  refresh(): void {
    this.loading = true;
    this.service.list().subscribe({
      next: (rows) => { this.rows = rows; this.applyFilter(); this.loading = false; },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load pre-authorizations';
        this.loading = false;
      },
    });
  }

  onStatusChange(): void { this.applyFilter(); }

  open(p: PreAuthorization): void {
    this.router.navigate(['/tenant/claims/preauth', p.id]);
  }

  private applyFilter(): void {
    if (!this.statusFilter) { this.filtered = this.rows; return; }
    this.filtered = this.rows.filter(r => r.status === this.statusFilter);
  }
}
