import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '../../../../../core/services/api.service';
import { ReportResponse } from '../../../../../core/services/report-envelope';

/**
 * Data layer for the fraud / SIU report. Backend at
 * {@code /api/v1/reports/fraud/*}; the gateway proxies the whole
 * prefix through to claims-service. §B Phase 11 adds trend + top-N +
 * AI calibration + investigator productivity endpoints alongside the
 * summary + XLSX MVP surface.
 */

export interface FraudReportData {
  casesOpened: number;
  confirmedCount: number;
  savingsComposite: string;
  confirmationRate: string;
  avgCycleTimeDays: string;
  reopenedCount: number;
  savingsPerCurrency: Record<string, string>;
}

export interface FraudReportParams {
  periodStart: string;
  periodEnd: string;
  reportingCurrency?: string;
}

export interface TrendPoint {
  month: string;             // YYYY-MM-01
  casesOpened: number;
  confirmedCount: number;
  dismissedCount: number;
}

export interface ProviderTopNRow {
  providerId: string;
  providerName: string | null;
  providerCode: string | null;
  confirmedCases: number;
  savingsNative: Record<string, string>;
  savingsComposite: string;
}

export interface MemberTopNRow {
  memberId: string;
  memberName: string | null;
  memberNumber: string | null;
  confirmedCases: number;
  savingsNative: Record<string, string>;
  savingsComposite: string;
}

export interface AiCalibrationRow {
  riskLevel: string;
  truePositives: number;
  falsePositives: number;
  totalFlags: number;
  precision4dp: string;
}

export interface AiCalibrationData {
  rows: AiCalibrationRow[];
  warnings: string[];
}

export interface InvestigatorProductivityRow {
  officerEmail: string;
  casesClosed: number;
  confirmedCount: number;
  dismissedCount: number;
  referredCount: number;
  avgCycleTimeDays: string;
}

function toParams(opts: FraudReportParams): Record<string, string> {
  const p: Record<string, string> = {
    periodStart: opts.periodStart,
    periodEnd:   opts.periodEnd,
  };
  if (opts.reportingCurrency) p['reportingCurrency'] = opts.reportingCurrency;
  return p;
}

@Injectable({ providedIn: 'root' })
export class FraudReportService {
  constructor(private api: ApiService) {}

  summary(opts: FraudReportParams): Observable<ReportResponse<FraudReportData>> {
    return this.api.get<ReportResponse<FraudReportData>>(
      '/reports/fraud/summary', toParams(opts));
  }

  exportExcel(opts: FraudReportParams): Observable<Blob> {
    return this.api.getBlob('/reports/fraud/summary/export', toParams(opts));
  }

  // §B Phase 11 — dedicated section endpoints. Each returns a plain
  // array/object (not the envelope shape), so the page owns the wrapping.

  trend(months: number = 12): Observable<TrendPoint[]> {
    return this.api.get<TrendPoint[]>('/reports/fraud/trend',
      { months: String(months) });
  }

  topProviders(opts: FraudReportParams, n: number = 10): Observable<ProviderTopNRow[]> {
    return this.api.get<ProviderTopNRow[]>('/reports/fraud/top-providers',
      { ...toParams(opts), n: String(n) });
  }

  topMembers(opts: FraudReportParams, n: number = 10): Observable<MemberTopNRow[]> {
    return this.api.get<MemberTopNRow[]>('/reports/fraud/top-members',
      { ...toParams(opts), n: String(n) });
  }

  aiCalibration(opts: FraudReportParams): Observable<AiCalibrationData> {
    return this.api.get<AiCalibrationData>('/reports/fraud/ai-calibration',
      toParams(opts));
  }

  investigatorProductivity(opts: FraudReportParams):
      Observable<InvestigatorProductivityRow[]> {
    return this.api.get<InvestigatorProductivityRow[]>(
      '/reports/fraud/investigator-productivity', toParams(opts));
  }
}
