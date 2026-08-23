import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';
import { ReportResponse } from './report-envelope';

/**
 * Phase 12 §B New Business Register data layer. Backend at
 * {@code /api/v1/reports/premium/new-business}; the gateway proxies the
 * whole {@code /api/v1/reports/premium} prefix to contributions-service.
 * Rows stay native-currency (parent-plan invariant #1); the envelope
 * carries per-currency subtotals + best-effort {@code fxRates}.
 */

/**
 * One row per policy that first bound within the reporting window (U6).
 * For HEALTH the row represents the member's first-ever Contribution
 * via the {@code member_first_contribution} materialised view.
 */
export interface NewBusinessRegisterRow {
  policyId: string;
  policySource: string;
  memberNumber: string;
  memberName: string;
  insuranceLine: string;
  schemeName: string;
  boundAt: string;
  writtenPremium: string;
  currencyCode: string;
  portfolioName: string;
  cohortName: string;
  coverageStart: string | null;
  coverageEnd: string | null;
}

export interface NewBusinessRegisterParams {
  periodStart: string;
  periodEnd: string;
  insuranceLine?: string | null;
  reportingCurrency?: string;
}

function newBusinessParams(opts: NewBusinessRegisterParams): Record<string, string> {
  const p: Record<string, string> = {
    periodStart: opts.periodStart,
    periodEnd:   opts.periodEnd,
  };
  if (opts.insuranceLine)     p['insuranceLine']     = opts.insuranceLine;
  if (opts.reportingCurrency) p['reportingCurrency'] = opts.reportingCurrency;
  return p;
}

@Injectable({ providedIn: 'root' })
export class NewBusinessRegisterReportService {
  constructor(private api: ApiService) {}

  get(opts: NewBusinessRegisterParams): Observable<ReportResponse<NewBusinessRegisterRow[]>> {
    return this.api.get<ReportResponse<NewBusinessRegisterRow[]>>(
      '/reports/premium/new-business', newBusinessParams(opts));
  }

  exportExcel(opts: NewBusinessRegisterParams): Observable<Blob> {
    return this.api.getBlob('/reports/premium/new-business/export/excel', newBusinessParams(opts));
  }
}
