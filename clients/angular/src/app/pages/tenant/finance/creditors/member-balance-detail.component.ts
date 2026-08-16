import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router } from '@angular/router';
import { forkJoin, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import {
  CtcPaymentRow,
  FinanceService,
  MemberBalance,
  MemberPayable,
  Note,
  Payment,
} from '../../../../core/services/finance.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { CurrencyFormatPipe } from '../../../../shared/pipes/currency-format.pipe';
import { HumanizePipe } from '../../../../shared/pipes/humanize.pipe';
import { ToastService } from '../../../../shared/components/toast/toast.service';

type Tab = 'payables' | 'ctcs' | 'payments' | 'notes';

/**
 * Member-side counterpart to {@link ProviderBalanceDetailComponent}. Member
 * balances are per-currency, so the summary section renders one row per
 * currency the member carries a balance in. The four tabs surface the
 * event streams that feed a member's balance: approved-and-outstanding
 * payables, offsetting CTCs, cash payments (with in-line revoke while a
 * run is still draft/approved), and manual adjustments.
 */
@Component({
  selector: 'app-member-balance-detail',
  standalone: true,
  imports: [CommonModule, IconComponent, SkeletonComponent, CurrencyFormatPipe, HumanizePipe],
  templateUrl: './member-balance-detail.component.html',
  styleUrl: './member-balance-detail.component.scss',
})
export class MemberBalanceDetailComponent implements OnInit {
  balances: MemberBalance[] = [];
  payables: MemberPayable[] = [];
  ctcs: CtcPaymentRow[] = [];
  payments: Payment[] = [];
  notes: Note[] = [];
  loading = false;
  errorMessage: string | null = null;
  revokingId: string | null = null;
  activeTab: Tab = 'payables';

  memberId: string | null = null;

  constructor(
    private finance: FinanceService,
    private route: ActivatedRoute,
    private router: Router,
    private toast: ToastService,
  ) {}

  ngOnInit(): void {
    this.memberId = this.route.snapshot.paramMap.get('id');
    if (!this.memberId) {
      this.errorMessage = 'No member id';
      return;
    }
    this.refresh();
  }

  refresh(): void {
    if (!this.memberId) return;
    this.loading = true;
    forkJoin({
      balances:   this.finance.getCreditorMemberDetail(this.memberId).pipe(catchError(() => of([] as MemberBalance[]))),
      payables:   this.finance.listOpenPayablesForMember(this.memberId).pipe(catchError(() => of([] as MemberPayable[]))),
      ctcs:       this.finance.listCtcPaymentsPaged({ memberId: this.memberId, size: 200 }).pipe(catchError(() => of({ content: [] as CtcPaymentRow[], total: 0, page: 0, size: 0, totalPages: 0 }))),
      payments:   this.finance.getPaymentsByMember(this.memberId).pipe(catchError(() => of([] as Payment[]))),
      notes: this.finance.getNotesByMember(this.memberId).pipe(catchError(() => of([] as Note[]))),
    }).subscribe({
      next: ({ balances, payables, ctcs, payments, notes }) => {
        this.balances = balances;
        this.payables = payables;
        this.ctcs     = ctcs.content;
        this.payments = payments;
        this.notes    = notes;
        this.loading  = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load member';
        this.loading = false;
      },
    });
  }

  setTab(tab: Tab): void { this.activeTab = tab; }

  back(): void {
    this.router.navigate(['/tenant/finance/creditors']);
  }

  balanceHistory(): void {
    if (this.memberId) this.router.navigate(['/tenant/finance/reports/balance-history/member', this.memberId]);
  }

  get displayName(): string {
    const withName = this.balances.find(b => b.memberName);
    if (withName?.memberName) return withName.memberName;
    const withCode = this.balances.find(b => b.memberCode);
    return withCode?.memberCode ?? (this.memberId ?? '—');
  }

  /**
   * Revoke a pending Payment from its parent run. Only enabled while
   * the payment is pending — the backend also enforces that the parent
   * run is in draft or approved status and 400s otherwise. Success
   * refreshes the tab so the row disappears; the payee's outstanding
   * balance is unchanged and next run generation picks them up again.
   */
  revoke(payment: Payment): void {
    if (payment.status !== 'pending' || this.revokingId) return;
    if (!confirm(`Revoke payment ${payment.paymentNumber}? The member's outstanding balance is unchanged; they will be included in the next run generation.`)) return;
    this.revokingId = payment.id;
    this.finance.revokePayment(payment.id).subscribe({
      next: () => {
        this.revokingId = null;
        this.toast.success(`Revoked ${payment.paymentNumber}`);
        this.refresh();
      },
      error: (err) => {
        this.revokingId = null;
        this.toast.error(err?.error?.detail || `Failed to revoke ${payment.paymentNumber}`);
      },
    });
  }
}
