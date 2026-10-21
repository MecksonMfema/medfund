import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';

/**
 * Client for finance-service's tax regulator report orchestrators :
 * VAT Return ({@code /api/v1/reports/regulatory/tax/vat-return/*}) and
 * Tax-Withheld Return ({@code /api/v1/reports/regulatory/tax/withheld-return/*}).
 * Both mirror the PMB Spend async submit → poll → XLSX shape.
 *
 * <p>Reporting currency is picker-driven: multi-currency tenants file
 * separate returns per operating currency (e.g. one ZWG return + one
 * USD return) in native units, no FX conversion. Omitting the currency
 * falls back to the tenant's country default (ZWL for ZW, ZAR for ZA).
 */
export interface TaxReturnReportRequest {
  periodStart: string;
  periodEnd: string;
  /** Optional ISO-4217 currency scoping the return. Empty / omitted →
   *  country default. Server persists the value in params_hash so
   *  ZWG + USD runs in the same window are distinct jobs. */
  reportingCurrency?: string | null;
  /** Archive the composed XLSX to regulatory_submission (requires fresh
   *  MFA). Defaults to false: dry-run export for review. */
  submit?: boolean;
  attestationNote?: string | null;
}

export interface TaxReturnJobSubmissionResponse {
  jobId: string;
  status: string;
  deduplicated: boolean;
}

@Injectable({ providedIn: 'root' })
export class TaxReportsService {
  private readonly vatBase = '/reports/regulatory/tax/vat-return';
  private readonly whtBase = '/reports/regulatory/tax/withheld-return';

  constructor(private api: ApiService) {}

  submitVatReturn(body: TaxReturnReportRequest): Observable<TaxReturnJobSubmissionResponse> {
    return this.api.post<TaxReturnJobSubmissionResponse>(`${this.vatBase}/submit`, body);
  }

  vatReturnXlsxUrl(jobId: string): string {
    return this.api.absoluteUrl(`${this.vatBase}/jobs/${jobId}/xlsx`);
  }

  submitWithheldReturn(body: TaxReturnReportRequest): Observable<TaxReturnJobSubmissionResponse> {
    return this.api.post<TaxReturnJobSubmissionResponse>(`${this.whtBase}/submit`, body);
  }

  withheldReturnXlsxUrl(jobId: string): string {
    return this.api.absoluteUrl(`${this.whtBase}/jobs/${jobId}/xlsx`);
  }
}
