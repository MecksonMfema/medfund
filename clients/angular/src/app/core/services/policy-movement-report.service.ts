import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';
import { ReportResponse } from './report-envelope';

/**
 * Phase 13 §C Phase 10 — POLICY_MOVEMENT report data layer. Backend at
 * {@code /api/v1/reports/policy-lifecycle/movement}; the gateway
 * forwards {@code /api/v1/reports/policy-lifecycle/*} to user-service.
 * Rows are native-currency (parent-plan invariant #1); the envelope
 * carries per-currency subtotals + best-effort {@code fxRates}.
 */
export interface PolicyMovementRow {
  policySource: string;
  insuranceLine: string;
  currencyCode: string;
  openingCount: number;
  newBusinessCount: number;
  renewedCount: number;
  lapsedCount: number;
  terminatedCount: number;
  closingCount: number;
  writtenPremiumAdded: string;
  writtenPremiumRemoved: string;
}

export interface PolicyMovementResult {
  rows: PolicyMovementRow[];
}

export interface PolicyMovementParams {
  periodStart: string;
  periodEnd: string;
  reportingCurrency?: string;
}

function movementParams(opts: PolicyMovementParams): Record<string, string> {
  const p: Record<string, string> = {
    periodStart: opts.periodStart,
    periodEnd:   opts.periodEnd,
  };
  if (opts.reportingCurrency) p['reportingCurrency'] = opts.reportingCurrency;
  return p;
}

@Injectable({ providedIn: 'root' })
export class PolicyMovementReportService {
  constructor(private api: ApiService) {}

  get(opts: PolicyMovementParams): Observable<ReportResponse<PolicyMovementResult>> {
    return this.api.get<ReportResponse<PolicyMovementResult>>(
      '/reports/policy-lifecycle/movement', movementParams(opts));
  }

  exportExcel(opts: PolicyMovementParams): Observable<Blob> {
    return this.api.getBlob(
      '/reports/policy-lifecycle/movement/export', movementParams(opts));
  }
}
