import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { forkJoin, of } from 'rxjs';
import {
  ContributionsService,
  Scheme,
} from '../../../../core/services/contributions.service';
import {
  WaitingPeriod,
  WaitingPeriodService,
} from '../../../../core/services/waiting-period.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { HumanizePipe } from '../../../../shared/pipes/humanize.pipe';

interface WaitingPeriodRow extends WaitingPeriod {
  schemeName?: string;
}

@Component({
  selector: 'app-waiting-periods-list',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent, SkeletonComponent, HumanizePipe],
  templateUrl: './waiting-periods-list.component.html',
  styleUrl: './waiting-periods-list.component.scss',
})
export class WaitingPeriodsListComponent implements OnInit {
  schemes: Scheme[] = [];
  selectedSchemeId = '';
  rows: WaitingPeriodRow[] = [];
  loading = false;
  errorMessage: string | null = null;
  pendingId: string | null = null;

  private schemeNames: Record<string, string> = {};

  constructor(
    private waitingService: WaitingPeriodService,
    private contributions: ContributionsService,
    private router: Router,
  ) {}

  ngOnInit(): void {
    this.loading = true;
    forkJoin({
      schemes: this.contributions.getSchemes(),
      rules: this.waitingService.list(),
    }).subscribe({
      next: ({ schemes, rules }) => {
        this.schemes = schemes;
        this.schemeNames = Object.fromEntries(schemes.map(s => [s.id, s.name]));
        this.applyRows(rules);
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load waiting periods';
        this.loading = false;
      },
    });
  }

  onSchemeChange(): void {
    this.loading = true;
    this.errorMessage = null;
    const stream = this.selectedSchemeId
      ? this.waitingService.list(this.selectedSchemeId)
      : this.waitingService.list();
    stream.subscribe({
      next: (rules) => { this.applyRows(rules); this.loading = false; },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load waiting periods';
        this.loading = false;
      },
    });
  }

  edit(r: WaitingPeriodRow): void {
    this.router.navigate(['/tenant/billing/waiting-periods', r.id, 'edit']);
  }

  remove(r: WaitingPeriodRow): void {
    if (!confirm(`Delete waiting period "${r.conditionType}" (${r.waitingDays} days)?`)) return;
    this.pendingId = r.id;
    this.waitingService.delete(r.id).subscribe({
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

  private applyRows(rules: WaitingPeriod[]): void {
    this.rows = rules.map(r => ({ ...r, schemeName: this.schemeNames[r.schemeId] }));
  }
}
