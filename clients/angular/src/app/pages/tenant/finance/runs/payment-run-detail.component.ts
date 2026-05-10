import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router } from '@angular/router';
import { forkJoin } from 'rxjs';
import {
  FinanceService,
  PaymentRun,
  PaymentRunItem,
} from '../../../../core/services/finance.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { CurrencyFormatPipe } from '../../../../shared/pipes/currency-format.pipe';
import { HumanizePipe } from '../../../../shared/pipes/humanize.pipe';

@Component({
  selector: 'app-payment-run-detail',
  standalone: true,
  imports: [CommonModule, IconComponent, SkeletonComponent, CurrencyFormatPipe, HumanizePipe],
  templateUrl: './payment-run-detail.component.html',
  styleUrl: './payment-run-detail.component.scss',
})
export class PaymentRunDetailComponent implements OnInit {
  run: PaymentRun | null = null;
  items: PaymentRunItem[] = [];
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
      this.errorMessage = 'No payment run id provided';
      return;
    }
    this.refresh(id);
  }

  refresh(id: string): void {
    this.loading = true;
    forkJoin({
      run: this.finance.getRun(id),
      items: this.finance.getRunItems(id),
    }).subscribe({
      next: ({ run, items }) => {
        this.run = run;
        this.items = items;
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load payment run';
        this.loading = false;
      },
    });
  }

  execute(): void {
    if (!this.run) return;
    if (!confirm(`Execute run ${this.run.runNumber}? This commits payments and is final.`)) return;
    this.transition('executed', () => this.finance.executeRun(this.run!.id));
  }

  approve(): void {
    if (!this.run) return;
    if (!confirm(`Approve run ${this.run.runNumber}?`)) return;
    this.transition('approved', () => this.finance.approveRun(this.run!.id));
  }

  cancelRun(): void {
    if (!this.run) return;
    if (!confirm(`Cancel run ${this.run.runNumber}?`)) return;
    this.transition('cancelled', () => this.finance.cancelRun(this.run!.id));
  }

  private transition(label: string, fn: () => import('rxjs').Observable<import('../../../../core/services/finance.service').PaymentRun>): void {
    this.busy = true;
    fn().subscribe({
      next: (run) => {
        this.run = run;
        this.successMessage = `Payment run ${run.runNumber} → ${label}.`;
        this.busy = false;
        this.refresh(run.id);
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || `Failed to ${label}`;
        this.busy = false;
      },
    });
  }

  back(): void {
    this.router.navigate(['/tenant/finance/runs']);
  }
}
