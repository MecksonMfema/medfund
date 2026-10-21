import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';
import { ReportResponse } from './report-envelope';

/**
 * Phase 12 §C endorsement register report data layer. Backend lives in
 * user-service at {@code /api/v1/reports/premium/endorsements}; the gateway
 * routes {@code /api/v1/reports/premium/endorsements} to user-service ahead
 * of the broader {@code /api/v1/reports/premium/*} wildcard so Fiber's
 * registration-order dispatch beats the contributions-service catchall.
 * Rows stay native-currency (parent-plan invariant #1); the envelope
 * carries per-currency subtotals of |premiumDelta| and best-effort
 * {@code fxRates}. The XLSX export additionally emits a
 * {@code DATA_ACCESS} security event server-side.
 */

/** One row per endorsement with {@code effectiveFrom} inside the window. */
export interface EndorsementRegisterRow {
  endorsementId: string;
  reference: string;
  policyId: string;
  policySource: string;
  memberName: string;
  insuranceLine: string;
  changeType: string;
  effectiveFrom: string;
  premiumDelta: string;
  currencyCode: string;
  status: string;
  draftActorEmail: string;
  draftAt: string;
  approveActorEmail: string | null;
  approveAt: string | null;
  commitActorEmail: string | null;
  commitAt: string | null;
  voidedReason: string | null;
}

export interface EndorsementRegisterParams {
  periodStart: string;
  periodEnd: string;
  insuranceLine?: string | null;
  status?: string | null;
  reportingCurrency?: string;
}

function registerParams(opts: EndorsementRegisterParams): Record<string, string> {
  const p: Record<string, string> = {
    periodStart: opts.periodStart,
    periodEnd:   opts.periodEnd,
  };
  if (opts.insuranceLine)     p['insuranceLine']     = opts.insuranceLine;
  if (opts.status)            p['status']            = opts.status;
  if (opts.reportingCurrency) p['reportingCurrency'] = opts.reportingCurrency;
  return p;
}

@Injectable({ providedIn: 'root' })
export class EndorsementRegisterReportService {
  constructor(private api: ApiService) {}

  get(opts: EndorsementRegisterParams): Observable<ReportResponse<EndorsementRegisterRow[]>> {
    return this.api.get<ReportResponse<EndorsementRegisterRow[]>>(
      '/reports/premium/endorsements', registerParams(opts));
  }

  exportExcel(opts: EndorsementRegisterParams): Observable<Blob> {
    return this.api.getBlob(
      '/reports/premium/endorsements/export/excel', registerParams(opts));
  }
}
