import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { CtcPayment, FinanceService } from '../../../../core/services/finance.service';
import { ConfirmService } from '../../../../shared/components/confirm-dialog/confirm.service';
import { ToastService } from '../../../../shared/components/toast/toast.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { StatCardComponent } from '../../../../shared/components/stat-card/stat-card.component';
import { HasPermissionDirective } from '../../../../shared/directives/has-permission.directive';
import { CurrencyFormatPipe } from '../../../../shared/pipes/currency-format.pipe';

@Component({
  selector: 'app-ctc-list',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    RouterLink,
    IconComponent,
    SkeletonComponent,
    StatCardComponent,
    HasPermissionDirective,
    CurrencyFormatPipe,
  ],
  templateUrl: './ctc-list.component.html',
  styleUrl: './ctc-list.component.scss',
})
export class CtcListComponent implements OnInit {
  rows: CtcPayment[] = [];
  loading = false;
  busyId: string | null = null;
  committed = false;
  q = '';

  constructor(
    private finance: FinanceService,
    private confirm: ConfirmService,
    private toast: ToastService,
    private route: ActivatedRoute,
  ) {}

  ngOnInit(): void {
    this.committed = !!this.route.snapshot.data?.['committed'];
    this.refresh();
  }

  refresh(): void {
    this.loading = true;
    this.finance.listCtcPayments(this.committed).subscribe({
      next: (rows) => { this.rows = rows; this.loading = false; },
      error: (err) => {
        this.loading = false;
        this.toast.error(err?.error?.detail || 'Failed to load CTC payments');
      },
    });
  }

  get filtered(): CtcPayment[] {
    if (!this.q.trim()) return this.rows;
    const q = this.q.trim().toLowerCase();
    return this.rows.filter(r =>
      r.id.toLowerCase().includes(q) ||
      (r.memberId || '').toLowerCase().includes(q) ||
      (r.groupId || '').toLowerCase().includes(q),
    );
  }

  get totalAmount(): number {
    return this.filtered.reduce((s, r) => s + Number(r.amount || 0), 0);
  }

  get currencyMix(): string {
    const set = new Set(this.filtered.map(r => r.currencyCode));
    return set.size === 1 ? [...set][0] : 'Mixed';
  }

  async commit(row: CtcPayment): Promise<void> {
    if (row.committed) return;
    const ok = await this.confirm.ask({
      title: 'Commit CTC payment',
      message: `Commit ${row.amount} ${row.currencyCode} for this allocation? This locks it against edits.`,
      confirmLabel: 'Commit',
    });
    if (!ok) return;

    this.busyId = row.id;
    this.finance.commitCtcPayment(row.id).subscribe({
      next: (updated) => {
        this.busyId = null;
        this.toast.success('CTC payment committed');
        const i = this.rows.findIndex(r => r.id === updated.id);
        if (i >= 0) this.rows[i] = updated;
        // On the pending list, drop committed rows.
        if (!this.committed && updated.committed) this.rows = this.rows.filter(r => r.id !== updated.id);
      },
      error: (err) => {
        this.busyId = null;
        this.toast.error(err?.error?.detail || 'Commit failed');
      },
    });
  }
}
