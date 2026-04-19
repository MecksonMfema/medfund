import { Injectable } from '@angular/core';
import { Observable, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { ApiService } from './api.service';

export interface PlatformStats {
  totalTenants: number;
  activeUsers: number;
  totalClaims: number;
  monthlyRevenue: number;
  tenantGrowth: number;
  userGrowth: number;
  claimsGrowth: number;
  revenueGrowth: number;
}

export interface SystemHealthItem {
  service: string;
  displayName: string;
  description: string;
  category: 'core' | 'support';
  status: 'healthy' | 'degraded' | 'down';
  latency: number;
}

const EMPTY_STATS: PlatformStats = {
  totalTenants: 0,
  activeUsers: 0,
  totalClaims: 0,
  monthlyRevenue: 0,
  tenantGrowth: 0,
  userGrowth: 0,
  claimsGrowth: 0,
  revenueGrowth: 0,
};

@Injectable({ providedIn: 'root' })
export class PlatformDashboardService {
  constructor(private api: ApiService) {}

  getPlatformStats(): Observable<PlatformStats> {
    return this.api.get<PlatformStats>('/platform/stats').pipe(
      catchError(() => of(EMPTY_STATS))
    );
  }

  getTenantGrowth(period?: string): Observable<any[]> {
    const params = period ? { period } : undefined;
    return this.api.get<any[]>('/platform/analytics/tenant-growth', params).pipe(
      catchError(() => of([]))
    );
  }

  getClaimsDistribution(): Observable<any[]> {
    return this.api.get<any[]>('/platform/analytics/claims-distribution').pipe(
      catchError(() => of([]))
    );
  }

  getRecentActivity(): Observable<any[]> {
    return this.api.get<any[]>('/platform/activity').pipe(
      catchError(() => of([]))
    );
  }

  getSystemHealth(): Observable<SystemHealthItem[]> {
    return this.api.get<SystemHealthItem[]>('/platform/health').pipe(
      catchError(() => of([]))
    );
  }

  getClaimsOverTime(period?: string): Observable<any[]> {
    const params = period ? { period } : undefined;
    return this.api.get<any[]>('/platform/analytics/claims-over-time', params).pipe(
      catchError(() => of([]))
    );
  }

  getRevenueByTenant(period?: string): Observable<any[]> {
    const params = period ? { period } : undefined;
    return this.api.get<any[]>('/platform/analytics/revenue-by-tenant', params).pipe(
      catchError(() => of([]))
    );
  }

  getMemberGrowth(period?: string): Observable<any[]> {
    const params = period ? { period } : undefined;
    return this.api.get<any[]>('/platform/analytics/member-growth', params).pipe(
      catchError(() => of([]))
    );
  }

  getBillingOverTime(period?: string): Observable<any[]> {
    const params = period ? { period } : undefined;
    return this.api.get<any[]>('/platform/analytics/billing-over-time', params).pipe(
      catchError(() => of([]))
    );
  }

  getBillingPaymentsOverTime(period?: string): Observable<any[]> {
    const params = period ? { period } : undefined;
    return this.api.get<any[]>('/platform/analytics/billing-payments-over-time', params).pipe(
      catchError(() => of([]))
    );
  }

  getClaimPayoutsOverTime(period?: string): Observable<any[]> {
    const params = period ? { period } : undefined;
    return this.api.get<any[]>('/platform/analytics/claim-payouts-over-time', params).pipe(
      catchError(() => of([]))
    );
  }
}
