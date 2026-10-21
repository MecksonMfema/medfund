import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';
import { ReportResponse } from './report-envelope';

/**
 * Phase 12 §B Premium Register data layer. Backend at
 * {@code /api/v1/reports/premium/register}; the gateway proxies the
 * whole {@code /api/v1/reports/premium} prefix to contributions-service.
 * Rows stay native-currency (parent-plan invariant #1); the envelope
 * carries per-currency subtotals + best-effort {@code fxRates}.
 */

/**
 * One row per (policy × source × period) in the reporting window,
 * enriched with member / scheme / portfolio / cohort labels so the reader
 * doesn't need to chase UUIDs.
 */
export interface PremiumRegisterRow {
  policyId: string;
  policySource: string;
  memberName: string;
  insuranceLine: string;
  schemeName: string;
  currencyCode: string;
  writtenPremium: string;
  earnedInPeriod: string;
  unearnedAtPeriodEnd: string;
  boundAt: string;
  coverageStart: string;
  coverageEnd: string;
  isNewBusiness: boolean;
  portfolioName: string;
  cohortName: string;
  periodStart: string;
  periodEnd: string;
}

export interface PremiumRegisterParams {
  periodStart: string;
  periodEnd: string;
  insuranceLine?: string | null;
  reportingCurrency?: string;
}

function registerParams(opts: PremiumRegisterParams): Record<string, string> {
  const p: Record<string, string> = {
    periodStart: opts.periodStart,
    periodEnd:   opts.periodEnd,
  };
  if (opts.insuranceLine)     p['insuranceLine']     = opts.insuranceLine;
  if (opts.reportingCurrency) p['reportingCurrency'] = opts.reportingCurrency;
  return p;
}

@Injectable({ providedIn: 'root' })
export class PremiumRegisterReportService {
  constructor(private api: ApiService) {}

  get(opts: PremiumRegisterParams): Observable<ReportResponse<PremiumRegisterRow[]>> {
    return this.api.get<ReportResponse<PremiumRegisterRow[]>>(
      '/reports/premium/register', registerParams(opts));
  }

  exportExcel(opts: PremiumRegisterParams): Observable<Blob> {
    return this.api.getBlob('/reports/premium/register/export/excel', registerParams(opts));
  }
}
