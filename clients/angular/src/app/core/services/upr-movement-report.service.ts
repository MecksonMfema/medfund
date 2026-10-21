import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';
import { ReportResponse } from './report-envelope';

/**
 * Phase 12 §B UPR Movement report data layer. Backend lives in
 * contributions-service at {@code /api/v1/reports/premium/upr-movement};
 * the gateway proxies the whole {@code /api/v1/reports/premium} prefix.
 * Rows stay native-currency (parent-plan invariant #1); the envelope
 * carries per-currency subtotals and best-effort {@code fxRates}. The
 * XLSX export additionally emits a {@code DATA_ACCESS} security event
 * server-side.
 */

/**
 * One row per (insurance_line, currency_code) in the reporting window.
 * openingUpr + writtenPremium − earnedPremium + endorsementDelta = closingUpr.
 */
export interface UprMovementRow {
  insuranceLine: string;
  currencyCode: string;
  openingUpr: string;
  writtenPremium: string;
  earnedPremium: string;
  endorsementDelta: string;
  closingUpr: string;
}

export interface UprMovementParams {
  periodStart: string;
  periodEnd: string;
  insuranceLine?: string | null;
  reportingCurrency?: string;
}

function uprParams(opts: UprMovementParams): Record<string, string> {
  const p: Record<string, string> = {
    periodStart: opts.periodStart,
    periodEnd:   opts.periodEnd,
  };
  if (opts.insuranceLine)     p['insuranceLine']     = opts.insuranceLine;
  if (opts.reportingCurrency) p['reportingCurrency'] = opts.reportingCurrency;
  return p;
}

@Injectable({ providedIn: 'root' })
export class UprMovementReportService {
  constructor(private api: ApiService) {}

  get(opts: UprMovementParams): Observable<ReportResponse<UprMovementRow[]>> {
    return this.api.get<ReportResponse<UprMovementRow[]>>(
      '/reports/premium/upr-movement', uprParams(opts));
  }

  exportExcel(opts: UprMovementParams): Observable<Blob> {
    return this.api.getBlob('/reports/premium/upr-movement/export/excel', uprParams(opts));
  }
}
