import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';
import { ReportResponse } from './report-envelope';

/**
 * Phase 13 §C Phase 10 — PROVIDER_NETWORK_UTILIZATION report data
 * layer. Lives under {@code /api/v1/reports/claims/}; the backend
 * enriches provider names + network tier via a batched call to
 * user-service (peer-down → placeholder names + envelope warning).
 */
export interface ProviderUtilizationRow {
  providerId: string;
  providerName: string;
  networkTier: string;
  insuranceLine: string;
  currencyCode: string;
  claimCount: number;
  totalClaimed: string;
  totalPaid: string;
  denialCount: number;
  uniqueMembers: number;
}

export interface NetworkTierTotals {
  networkTier: string;
  providerCount: number;
  claimCount: number;
  totalClaimed: string;
  totalPaid: string;
  denialCount: number;
  uniqueMembers: number;
}

export interface ProviderUtilizationResult {
  summary: Record<string, NetworkTierTotals>;
  detail: ProviderUtilizationRow[];
}

export interface ProviderUtilizationParams {
  periodStart: string;
  periodEnd: string;
  insuranceLine?: string | null;
  networkTier?: string | null;
  reportingCurrency?: string;
}

function utilizationParams(opts: ProviderUtilizationParams): Record<string, string> {
  const p: Record<string, string> = {
    periodStart: opts.periodStart,
    periodEnd:   opts.periodEnd,
  };
  if (opts.insuranceLine)     p['insuranceLine']     = opts.insuranceLine;
  if (opts.networkTier)       p['networkTier']       = opts.networkTier;
  if (opts.reportingCurrency) p['reportingCurrency'] = opts.reportingCurrency;
  return p;
}

@Injectable({ providedIn: 'root' })
export class ProviderNetworkUtilizationReportService {
  constructor(private api: ApiService) {}

  get(opts: ProviderUtilizationParams): Observable<ReportResponse<ProviderUtilizationResult>> {
    return this.api.get<ReportResponse<ProviderUtilizationResult>>(
      '/reports/claims/provider-network-utilization', utilizationParams(opts));
  }

  exportExcel(opts: ProviderUtilizationParams): Observable<Blob> {
    return this.api.getBlob(
      '/reports/claims/provider-network-utilization/export', utilizationParams(opts));
  }
}
