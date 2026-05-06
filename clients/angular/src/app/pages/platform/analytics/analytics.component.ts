import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { StatCardComponent } from '../../../shared/components/stat-card/stat-card.component';
import { AreaChartComponent } from '../../../shared/components/charts/area-chart/area-chart.component';
import { IconComponent } from '../../../shared/components/icon/icon.component';
import { PlatformDashboardService, SystemHealthItem } from '../../../core/services/platform-dashboard.service';

type MoneyFlowTab = 'claims' | 'billing' | 'billing-payments' | 'claim-payouts';

@Component({
  selector: 'app-analytics',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    StatCardComponent,
    AreaChartComponent,
    IconComponent,
  ],
  templateUrl: './analytics.component.html',
  styleUrl: './analytics.component.scss',
})
export class AnalyticsComponent implements OnInit {
  selectedPeriod = 'year';
  moneyFlowTab: MoneyFlowTab = 'claims';

  // Stats
  claimsProcessed = 0;
  totalRevenue = 0;
  activeUsers = 0;
  totalTenants = 0;

  // Charts
  tenantGrowthData: any[] = [];
  tenantGrowthMax = 5;
  memberGrowthData: any[] = [];
  memberGrowthMax = 5;
  claimsOverTime: any[] = [];
  billingOverTime: any[] = [];
  billingPaymentsOverTime: any[] = [];
  claimPayoutsOverTime: any[] = [];

  // Service health
  serviceHealth: SystemHealthItem[] = [];

  readonly periods = [
    { value: 'all',   label: 'All Time' },
    { value: 'year',  label: 'Last Year' },
    { value: 'month', label: 'Last Month' },
    { value: 'week',  label: 'Last Week' },
  ];

  constructor(private dashboardService: PlatformDashboardService) {}

  ngOnInit(): void {
    this.dashboardService.getPlatformStats().subscribe((stats) => {
      this.claimsProcessed = stats.totalClaims;
      this.totalRevenue = stats.monthlyRevenue;
      this.activeUsers = stats.activeUsers;
      this.totalTenants = stats.totalTenants;
    });

    this.dashboardService.getSystemHealth().subscribe((health) => {
      this.serviceHealth = health;
    });

    this.loadCharts();
  }

  selectPeriod(value: string): void {
    this.selectedPeriod = value;
    this.loadCharts();
  }

  get periodParam(): string | undefined {
    return this.selectedPeriod === 'all' ? undefined : this.selectedPeriod;
  }

  get xAxisLabel(): string {
    switch (this.selectedPeriod) {
      case 'week':  return 'Day';
      case 'month': return 'Date';
      case 'year':  return 'Month';
      case 'all':   return 'Month';
      default:      return 'Month';
    }
  }

  get periodLabel(): string {
    return this.periods.find(p => p.value === this.selectedPeriod)?.label ?? '';
  }

  /** All chart data comes from the backend as [{name, value}] — no client-side label mapping. */
  private loadCharts(): void {
    const p = this.periodParam;

    const toSeries = (d: any[]) => d.map(p => ({ name: p.name, value: p.value ?? p.count ?? 0 }));
    const yMax = (s: any[]) => { const m = Math.max(...s.map(p => p.value)); return m > 0 ? Math.ceil(m * 1.2) : 5; };

    this.dashboardService.getTenantGrowth(p).subscribe(d => {
      const s = toSeries(d);
      this.tenantGrowthData = [{ name: 'Tenants', series: s }];
      this.tenantGrowthMax = yMax(s);
    });

    this.dashboardService.getMemberGrowth(p).subscribe(d => {
      const s = toSeries(d);
      this.memberGrowthData = [{ name: 'Members', series: s }];
      this.memberGrowthMax = yMax(s);
    });

    this.dashboardService.getClaimsOverTime(p).subscribe(d => {
      this.claimsOverTime = [{ name: 'Claims', series: toSeries(d) }];
    });

    this.dashboardService.getBillingOverTime(p).subscribe(d => {
      this.billingOverTime = [{ name: 'Billed', series: toSeries(d) }];
    });

    this.dashboardService.getBillingPaymentsOverTime(p).subscribe(d => {
      this.billingPaymentsOverTime = [{ name: 'Received', series: toSeries(d) }];
    });

    this.dashboardService.getClaimPayoutsOverTime(p).subscribe(d => {
      this.claimPayoutsOverTime = [{ name: 'Paid Out', series: toSeries(d) }];
    });
  }

  statusClass(status: string): string {
    switch (status) {
      case 'healthy':  return 'status-healthy';
      case 'degraded': return 'status-degraded';
      case 'down':     return 'status-down';
      default: return '';
    }
  }
}
