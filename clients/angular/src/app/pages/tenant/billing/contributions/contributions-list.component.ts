import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ContributionsService, Contribution } from '../../../../core/services/contributions.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { CurrencyFormatPipe } from '../../../../shared/pipes/currency-format.pipe';
import { HumanizePipe } from '../../../../shared/pipes/humanize.pipe';

const STATUSES = ['pending', 'paid', 'overdue', 'written_off'] as const;

@Component({
  selector: 'app-contributions-list',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent, SkeletonComponent, CurrencyFormatPipe, HumanizePipe],
  templateUrl: './contributions-list.component.html',
  styleUrl: './contributions-list.component.scss',
})
export class ContributionsListComponent implements OnInit {
  status: typeof STATUSES[number] = 'pending';
  rows: Contribution[] = [];
  loading = false;
  errorMessage: string | null = null;

  readonly statuses = STATUSES;

  constructor(private contributions: ContributionsService) {}

  ngOnInit(): void {
    this.refresh();
  }

  refresh(): void {
    this.loading = true;
    this.errorMessage = null;
    this.contributions.getContributionsByStatus(this.status).subscribe({
      next: (rows) => { this.rows = rows; this.loading = false; },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load contributions';
        this.loading = false;
      },
    });
  }
}
