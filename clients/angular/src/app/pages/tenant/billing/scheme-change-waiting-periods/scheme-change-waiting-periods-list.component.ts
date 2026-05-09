import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import {
  SchemeChangeWaitingPeriod,
  WaitingPeriodService,
} from '../../../../core/services/waiting-period.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { HumanizePipe } from '../../../../shared/pipes/humanize.pipe';

@Component({
  selector: 'app-scheme-change-waiting-periods-list',
  standalone: true,
  imports: [CommonModule, RouterLink, IconComponent, SkeletonComponent, HumanizePipe],
  templateUrl: './scheme-change-waiting-periods-list.component.html',
  styleUrl: './scheme-change-waiting-periods-list.component.scss',
})
export class SchemeChangeWaitingPeriodsListComponent implements OnInit {
  rows: SchemeChangeWaitingPeriod[] = [];
  loading = false;
  errorMessage: string | null = null;
  pendingId: string | null = null;

  constructor(private service: WaitingPeriodService, private router: Router) {}

  ngOnInit(): void { this.refresh(); }

  refresh(): void {
    this.loading = true;
    this.errorMessage = null;
    this.service.listSchemeChange().subscribe({
      next: (rows) => { this.rows = rows; this.loading = false; },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load scheme-change waiting periods';
        this.loading = false;
      },
    });
  }

  edit(r: SchemeChangeWaitingPeriod): void {
    this.router.navigate(['/tenant/billing/scheme-change-waiting-periods', r.id, 'edit']);
  }

  remove(r: SchemeChangeWaitingPeriod): void {
    if (!confirm(`Delete ${r.changeType.toLowerCase()} rule for ${r.benefitType || 'all benefits'}?`)) return;
    this.pendingId = r.id;
    this.service.deleteSchemeChange(r.id).subscribe({
      next: () => { this.rows = this.rows.filter(x => x.id !== r.id); this.pendingId = null; },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Delete failed';
        this.pendingId = null;
      },
    });
  }
}
