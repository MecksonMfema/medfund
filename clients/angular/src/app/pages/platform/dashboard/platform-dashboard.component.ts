import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { StatCardComponent } from '../../../shared/components/stat-card/stat-card.component';
import { ActivityFeedComponent, ActivityItem } from '../../../shared/components/activity-feed/activity-feed.component';
import { AreaChartComponent } from '../../../shared/components/charts/area-chart/area-chart.component';
import { PieChartComponent } from '../../../shared/components/charts/pie-chart/pie-chart.component';
import { IconComponent } from '../../../shared/components/icon/icon.component';
import {
  PlatformDashboardService,
  PlatformStats,
  SystemHealthItem,
} from '../../../core/services/platform-dashboard.service';
import { NavigationService } from '../../../core/services/navigation.service';

@Component({
  selector: 'app-platform-dashboard',
  standalone: true,
  imports: [
    CommonModule,
    StatCardComponent,
    ActivityFeedComponent,
    AreaChartComponent,
    PieChartComponent,
    IconComponent,
  ],
  templateUrl: './platform-dashboard.component.html',
  styleUrl: './platform-dashboard.component.scss',
})
export class PlatformDashboardComponent implements OnInit {
  userName = 'Admin';
  today = new Date();
  stats: PlatformStats = {
    totalTenants: 0,
    activeUsers: 0,
    totalClaims: 0,
    monthlyRevenue: 0,
    tenantGrowth: 0,
    userGrowth: 0,
    claimsGrowth: 0,
    revenueGrowth: 0,
  };
  tenantGrowthData: any[] = [];
  tenantGrowthMax = 5;
  claimsDistribution: any[] = [];
  claimsHasData = false;
  claimsColors: Record<string, string> = {
    'Approved':        '#2EC4B6',
    'Pending':         '#FF9F1C',
    'Rejected':        '#E71D36',
    'In Adjudication': '#00B4D8',
    'Submitted':       '#90E0EF',
  };
  recentActivity: ActivityItem[] = [];
  systemHealth: SystemHealthItem[] = [];

  constructor(
    private dashboardService: PlatformDashboardService,
    private navService: NavigationService,
  ) {}

  ngOnInit(): void {
    const info = this.navService.getUserInfo();
    this.userName = info.fullName.split(' ')[0] || 'Admin';

    this.dashboardService.getPlatformStats().subscribe((s) => (this.stats = s));
    this.dashboardService.getTenantGrowth().subscribe((d) => {
      const months = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
      const series = months.map((month, i) => ({
        name: month,
        value: d[i]?.count ?? 0,
      }));
      this.tenantGrowthData = [{ name: 'Tenants', series }];
      const maxVal = Math.max(...series.map(p => p.value));
      this.tenantGrowthMax = maxVal > 0 ? Math.ceil(maxVal * 1.2) : 5;
    });
    this.dashboardService.getClaimsDistribution().subscribe((d) => {
      const STATUS_LABELS: Record<string, string> = {
        approved:        'Approved',
        pending:         'Pending',
        rejected:        'Rejected',
        in_adjudication: 'In Adjudication',
        submitted:       'Submitted',
      };
      const mapped = d.map(item => ({
        name:  STATUS_LABELS[item['status']] ?? item['status'],
        value: item['count'] ?? 0,
      }));
      const hasData = mapped.some(item => item.value > 0);
      // When no claims exist yet, show a placeholder so the chart renders
      this.claimsDistribution = hasData ? mapped : [
        { name: 'Approved',        value: 1 },
        { name: 'Pending',         value: 1 },
        { name: 'In Adjudication', value: 1 },
        { name: 'Rejected',        value: 1 },
        { name: 'Submitted',       value: 1 },
      ];
      this.claimsHasData = hasData;
    });
    this.dashboardService.getRecentActivity().subscribe((d) => (this.recentActivity = d));
    this.dashboardService.getSystemHealth().subscribe((d) => (this.systemHealth = d));
  }

  get coreServices() {
    return this.systemHealth.filter(h => h.category === 'core');
  }

  get supportServices() {
    return this.systemHealth.filter(h => h.category === 'support');
  }

  get overallHealthClass(): string {
    if (!this.systemHealth.length) return 'degraded';
    if (this.systemHealth.some((h) => h.status === 'down')) return 'down';
    if (this.systemHealth.some((h) => h.status === 'degraded')) return 'degraded';
    return 'healthy';
  }

  get overallHealthLabel(): string {
    if (!this.systemHealth.length) return 'Loading…';
    if (this.systemHealth.some((h) => h.status === 'down')) return 'Outage';
    if (this.systemHealth.some((h) => h.status === 'degraded')) return 'Degraded';
    return 'All Systems Operational';
  }
}
