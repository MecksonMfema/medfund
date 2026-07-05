import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Subscription, forkJoin, of } from 'rxjs';
import { catchError, distinctUntilChanged, switchMap } from 'rxjs/operators';
import { StatCardComponent } from '../../shared/components/stat-card/stat-card.component';
import { AreaChartComponent } from '../../shared/components/charts/area-chart/area-chart.component';
import { AdminService, TenantStats, TenantCharts, TrendPoint } from '../../core/services/admin.service';
import { TenantService } from '../../core/services/tenant.service';

const EMPTY_STATS: TenantStats = {
  totalStaff: 0, activeStaff: 0, suspendedStaff: 0, pendingStaff: 0,
  totalMembers: 0, activeMembers: 0, enrolledMembers: 0,
  newMembersThisMonth: 0, newGroupsThisMonth: 0,
  claimsNewTasks: 0, claimsThisMonth: 0,
  claimsAcceptedThisMonth: 0, claimsRejectedThisMonth: 0,
  schemesActive: 0,
  contributionsPending: 0, invoicesOutstanding: 0,
  contributionsAmountThisMonth: 0, contributionsAmountThisYear: 0,
  paymentsPending: 0, paymentsAmountThisMonth: 0, paymentsAmountThisYear: 0,
  paymentsAdvanceCount: 0, paymentsAdvanceAmount: 0, paymentsFailedCount: 0,
  claimsShortfallAmount: 0,
  contributionsAmountThisMonthByCurrency: {},
  contributionsAmountThisYearByCurrency:  {},
  paymentsAmountThisMonthByCurrency:      {},
  paymentsAmountThisYearByCurrency:       {},
};

const EMPTY_CHARTS: TenantCharts = {
  claimsByMonth: [], contributionsAmountByMonth: [], paymentsAmountByMonth: [],
  claimsByMonthByCurrency: {},
  contributionsAmountByMonthByCurrency: {},
  paymentsAmountByMonthByCurrency: {},
};

@Component({
  selector: 'app-claims',
  standalone: true,
  imports: [CommonModule, StatCardComponent, AreaChartComponent],
  templateUrl: './claims.component.html',
  styleUrl: './claims.component.scss',
})
export class ClaimsComponent implements OnInit, OnDestroy {
  stats: TenantStats = { ...EMPTY_STATS };
  monthlyClaimsData: { name: string; series: TrendPoint[] }[] = [];
  monthlyClaimsMax = 5;
  loading = true;

  private subs: Subscription[] = [];

  constructor(
    private adminService: AdminService,
    private tenantService: TenantService,
  ) {}

  ngOnInit(): void {
    this.subs.push(this.tenantService.tenant$.pipe(
      distinctUntilChanged((a, b) => a?.id === b?.id),
      switchMap(t => {
        if (!t?.id) return of(null);
        this.loading = true;
        return forkJoin({
          stats:  this.adminService.getTenantStats(t.id),
          charts: this.adminService.getTenantCharts(t.id).pipe(
                    catchError(() => of<TenantCharts>(EMPTY_CHARTS)),
                  ),
        });
      }),
    ).subscribe({
      next: (result) => {
        if (!result) return;
        this.stats = { ...EMPTY_STATS, ...result.stats };
        const series = result.charts.claimsByMonth ?? [];
        this.monthlyClaimsData = [{ name: 'Claims', series }];
        const maxVal = series.reduce((m, p) => Math.max(m, Number(p.value) || 0), 0);
        this.monthlyClaimsMax = maxVal > 0 ? Math.ceil(maxVal * 1.2) : 5;
        this.loading = false;
      },
      error: () => { this.loading = false; },
    }));
  }

  ngOnDestroy(): void {
    this.subs.forEach(s => s.unsubscribe());
  }
}
