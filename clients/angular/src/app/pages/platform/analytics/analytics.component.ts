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

  private loadCharts(): void {
    const p = this.periodParam;
    const labels = this.axisLabels();

    this.dashboardService.getTenantGrowth(p).subscribe((d) => {
      const series = labels.map((label, i) => ({
        name: label,
        value: d[i]?.count ?? d[i]?.value ?? 0,
      }));
      this.tenantGrowthData = [{ name: 'Tenants', series }];
      const max = Math.max(...series.map(s => s.value));
      this.tenantGrowthMax = max > 0 ? Math.ceil(max * 1.2) : 5;
    });

    this.dashboardService.getMemberGrowth(p).subscribe((d) => {
      const series = labels.map((label, i) => ({
        name: label,
        value: d[i]?.count ?? d[i]?.value ?? 0,
      }));
      this.memberGrowthData = [{ name: 'Members', series }];
      const max = Math.max(...series.map(s => s.value));
      this.memberGrowthMax = max > 0 ? Math.ceil(max * 1.2) : 5;
    });

    this.dashboardService.getClaimsOverTime(p).subscribe((d) => {
      this.claimsOverTime = [{ name: 'Claims', series: labels.map((label, i) => ({ name: label, value: d[i]?.count ?? d[i]?.value ?? 0 })) }];
    });

    this.dashboardService.getBillingOverTime(p).subscribe((d) => {
      this.billingOverTime = [{ name: 'Billed', series: labels.map((label, i) => ({ name: label, value: d[i]?.amount ?? d[i]?.value ?? 0 })) }];
    });

    this.dashboardService.getBillingPaymentsOverTime(p).subscribe((d) => {
      this.billingPaymentsOverTime = [{ name: 'Received', series: labels.map((label, i) => ({ name: label, value: d[i]?.amount ?? d[i]?.value ?? 0 })) }];
    });

    this.dashboardService.getClaimPayoutsOverTime(p).subscribe((d) => {
      this.claimPayoutsOverTime = [{ name: 'Paid Out', series: labels.map((label, i) => ({ name: label, value: d[i]?.amount ?? d[i]?.value ?? 0 })) }];
    });

  }

  /** Returns x-axis labels appropriate for the selected period. */
  private axisLabels(): string[] {
    const today = new Date();

    switch (this.selectedPeriod) {
      case 'week':
        // Last 7 days — all shown, formatted as "Mon 13"
        return Array.from({ length: 7 }, (_, i) => {
          const d = new Date(today);
          d.setDate(today.getDate() - (6 - i));
          return d.toLocaleDateString('en-GB', { weekday: 'short', day: 'numeric' });
        });

      case 'month':
        // Last 30 days — 10 evenly spaced actual dates, formatted as "21 Mar"
        return Array.from({ length: 10 }, (_, i) => {
          const daysAgo = Math.round(29 * (1 - i / 9));
          const d = new Date(today);
          d.setDate(today.getDate() - daysAgo);
          return d.toLocaleDateString('en-GB', { day: 'numeric', month: 'short' });
        });

      case 'year':
      case 'all':
      default:
        return ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun',
                'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
    }
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
