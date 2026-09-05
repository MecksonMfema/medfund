import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';
import { ReportResponse } from './report-envelope';

/**
 * Phase 5/6 (financial-reporting Phase 18) — five executive KPIs served by the
 * finance-service {@code KpiComposerService}. Backend routes live under
 * {@code /api/v1/reports/kpi/*}; the gateway proxies the whole prefix.
 *
 * <p>Every KPI GET returns the uniform {@link ReportResponse} envelope wrapped
 * around a {@link KpiReportData} payload — a composite ratio computed on the
 * reporting currency plus native per-currency ratios (G34: never cross-currency
 * conversion in the ratio itself). Batch dashboard endpoint returns a map of
 * envelopes keyed by report key. Trend endpoints return an array of
 * {@link KpiTrendPoint}, oldest-first, half-open period per bucket.
 */

/** The five KPI report keys (Phase 1 {@code ReportKey} enum). */
export type KpiKey =
  | 'LOSS_RATIO_KPI'
  | 'EXPENSE_RATIO'
  | 'COMBINED_RATIO'
  | 'CLAIMS_FREQUENCY'
  | 'AVERAGE_SEVERITY';

/** Client-side filter set — mirrors the query params on every KPI endpoint. */
export interface KpiFilters {
  insuranceLine?: string;
  schemeId?: string;
  producerId?: string;
  reportingCurrency?: string;
}

/**
 * Per-currency ratio row inside {@link KpiReportData}. Native currency, no FX
 * conversion — mirrors Java {@code KpiValue}. Denominator + numerator are the
 * raw building blocks; ratio is server-computed at RATIO_SCALE=6.
 */
export interface KpiValue {
  ratio:        number | null;
  numerator:    number;
  denominator:  number;
  currencyCode: string;
}

/**
 * Payload wrapped by {@link ReportResponse} on every KPI endpoint. Composite
 * numerator/denominator are in the requested {@code reportingCurrency} after
 * FX conversion; {@code perCurrency} keeps the raw native rows for
 * transparency. {@code basisNote} is populated only for COMBINED_RATIO
 * (server sets it to {@code MIXED_LOSS_EARNED_EXPENSE_WRITTEN} per NAIC
 * convention when the two component ratios use different bases).
 */
export interface KpiReportData {
  compositeRatio:       number | null;
  compositeNumerator:   number;
  compositeDenominator: number;
  basisNote:            string | null;
  perCurrency:          Record<string, KpiValue>;
}

/** Batch dashboard envelope — keyed by KPI report key. */
export interface KpiDashboardResponse {
  tiles: Partial<Record<KpiKey, ReportResponse<KpiReportData>>>;
}

/**
 * One point on a KPI trend. Period is half-open {@code [periodStart,
 * periodEnd)} — matches the composer's bucket semantics and every existing
 * aggregate SQL predicate.
 */
export interface KpiTrendPoint {
  periodStart: string;
  periodEnd:   string;
  composite:   KpiReportData;
  perCurrency: Record<string, KpiValue>;
  warnings:    string[];
}

function filterParams(f: KpiFilters, extra: Record<string, string> = {}): Record<string, string> {
  const p: Record<string, string> = { ...extra };
  if (f.insuranceLine)     p['insuranceLine']     = f.insuranceLine;
  if (f.schemeId)          p['schemeId']          = f.schemeId;
  if (f.producerId)        p['producerId']        = f.producerId;
  if (f.reportingCurrency) p['reportingCurrency'] = f.reportingCurrency;
  return p;
}

@Injectable({ providedIn: 'root' })
export class ExecutiveKpiService {
  constructor(private api: ApiService) {}

  /** All five tiles in one round-trip; missing tiles indicate the tenant has
   *  disabled that report — dashboard endpoint 403s if every KPI is off. */
  dashboard(filters: KpiFilters): Observable<KpiDashboardResponse> {
    return this.api.get<KpiDashboardResponse>('/reports/kpi/dashboard', filterParams(filters));
  }

  /** Time series for a single KPI. {@code windowMonths} must be 12 or 24. */
  trend(key: KpiKey, filters: KpiFilters, windowMonths: 12 | 24 = 12): Observable<KpiTrendPoint[]> {
    return this.api.get<KpiTrendPoint[]>(
      `/reports/kpi/${key}/trend`,
      filterParams(filters, { windowMonths: String(windowMonths) }),
    );
  }

  /** On-demand XLSX export — Summary + 12-month Trend sheets. Requires
   *  {@code periodStart} + {@code periodEnd} on the filter set; the caller
   *  supplies them from the currently displayed period. */
  exportExcel(
    key: KpiKey,
    filters: KpiFilters & { periodStart: string; periodEnd: string },
  ): Observable<Blob> {
    return this.api.getBlob(`/reports/kpi/${key}/export/excel`, filterParams(filters, {
      periodStart: filters.periodStart,
      periodEnd:   filters.periodEnd,
    }));
  }
}
