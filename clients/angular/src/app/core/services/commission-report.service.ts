import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';
import { ReportResponse } from './report-envelope';

/**
 * Phase 11 §A commission-report data layer. Backend lives in finance-service
 * under {@code /api/v1/reports/commission/*}; the gateway proxies the
 * whole prefix. Every JSON GET returns the uniform {@link ReportResponse}
 * envelope; rows stay native-currency (G25), the envelope carries
 * per-currency subtotals + best-effort {@code fxRates}. XLSX exports
 * additionally emit a {@code DATA_ACCESS} security event server-side
 * (parent-plan invariant #3).
 */

export type ClawbackSource = 'MEMBER_LAPSE' | 'CONTRIBUTION_REVOKE';

/** One row per commission_transaction in the reporting period (native). */
export interface CommissionStatementRow {
  commissionTransactionId: string;
  reference: string;
  producerId: string;
  producerCode: string;
  producerName: string;
  producerHomeCurrency: string;
  contributionId: string;
  memberId: string;
  insuranceLine: string;
  rateCardId: string | null;
  rateCardName: string | null;
  appliedRatePct: string;
  contributionAmount: string;
  nativeAmount: string;
  nativeCurrency: string;
  status: string;
  occurredAt: string;
}

/** One row per clawback_event in the reporting period (native). */
export interface ClawbackRegisterRow {
  clawbackEventId: string;
  source: ClawbackSource;
  triggeringEventRef: string;
  memberId: string;
  producerId: string;
  producerCode: string;
  producerName: string;
  commissionTransactionId: string;
  commissionReference: string;
  nativeAmount: string;
  nativeCurrency: string;
  reason: string | null;
  occurredAt: string;
}

export interface CommissionStatementParams {
  periodStart: string;
  periodEnd: string;
  producerId?: string | null;
  reportingCurrency?: string;
}

export interface ClawbackRegisterParams {
  periodStart: string;
  periodEnd: string;
  producerId?: string | null;
  source?: ClawbackSource | '';
  reportingCurrency?: string;
}

function statementParams(opts: CommissionStatementParams): Record<string, string> {
  const p: Record<string, string> = {
    periodStart: opts.periodStart,
    periodEnd:   opts.periodEnd,
  };
  if (opts.producerId)        p['producerId']        = opts.producerId;
  if (opts.reportingCurrency) p['reportingCurrency'] = opts.reportingCurrency;
  return p;
}

function clawbackParams(opts: ClawbackRegisterParams): Record<string, string> {
  const p: Record<string, string> = {
    periodStart: opts.periodStart,
    periodEnd:   opts.periodEnd,
  };
  if (opts.producerId)        p['producerId']        = opts.producerId;
  if (opts.source)            p['source']            = opts.source;
  if (opts.reportingCurrency) p['reportingCurrency'] = opts.reportingCurrency;
  return p;
}

@Injectable({ providedIn: 'root' })
export class CommissionReportService {
  constructor(private api: ApiService) {}

  getStatement(opts: CommissionStatementParams): Observable<ReportResponse<CommissionStatementRow[]>> {
    return this.api.get<ReportResponse<CommissionStatementRow[]>>(
      '/reports/commission/statement', statementParams(opts));
  }

  exportStatementExcel(opts: CommissionStatementParams): Observable<Blob> {
    return this.api.getBlob('/reports/commission/statement/export/excel', statementParams(opts));
  }

  getClawbackRegister(opts: ClawbackRegisterParams): Observable<ReportResponse<ClawbackRegisterRow[]>> {
    return this.api.get<ReportResponse<ClawbackRegisterRow[]>>(
      '/reports/commission/clawback-register', clawbackParams(opts));
  }

  exportClawbackRegisterExcel(opts: ClawbackRegisterParams): Observable<Blob> {
    return this.api.getBlob('/reports/commission/clawback-register/export/excel', clawbackParams(opts));
  }
}
