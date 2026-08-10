import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router } from '@angular/router';
import { forkJoin, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import {
  FinanceService,
  Note,
  Payment,
  ProviderBalance,
} from '../../../../core/services/finance.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { CurrencyFormatPipe } from '../../../../shared/pipes/currency-format.pipe';
import { HumanizePipe } from '../../../../shared/pipes/humanize.pipe';

type Tab = 'payments' | 'notes';

@Component({
  selector: 'app-provider-balance-detail',
  standalone: true,
  imports: [CommonModule, IconComponent, SkeletonComponent, CurrencyFormatPipe, HumanizePipe],
  templateUrl: './provider-balance-detail.component.html',
  styleUrl: './provider-balance-detail.component.scss',
})
export class ProviderBalanceDetailComponent implements OnInit {
  balance: ProviderBalance | null = null;
  payments: Payment[] = [];
  notes: Note[] = [];
  loading = false;
  errorMessage: string | null = null;
  activeTab: Tab = 'payments';

  constructor(
    private finance: FinanceService,
    private route: ActivatedRoute,
    private router: Router,
  ) {}

  ngOnInit(): void {
    const providerId = this.route.snapshot.paramMap.get('id');
    if (!providerId) {
      this.errorMessage = 'No provider id';
      return;
    }
    this.refresh(providerId);
  }

  refresh(providerId: string): void {
    this.loading = true;
    forkJoin({
      balance: this.finance.getCreditorProviderDetail(providerId).pipe(catchError(() => of(null))),
      payments: this.finance.getPaymentsByProvider(providerId).pipe(catchError(() => of([] as Payment[]))),
      notes: this.finance.getNotesByProvider(providerId).pipe(catchError(() => of([] as Note[]))),
    }).subscribe({
      next: ({ balance, payments, notes }) => {
        this.balance = balance;
        this.payments = payments;
        this.notes = notes;
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load provider';
        this.loading = false;
      },
    });
  }

  setTab(tab: Tab): void { this.activeTab = tab; }

  back(): void {
    this.router.navigate(['/tenant/finance/creditors']);
  }
}
