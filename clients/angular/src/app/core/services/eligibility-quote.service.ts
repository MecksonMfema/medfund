import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';

/**
 * Payload for {@code POST /api/v1/eligibility-quote} on claims-service.
 * The member is named by the friendly {@code memberNumber} — never a raw
 * UUID (per {@code feedback_no_raw_id_inputs}).
 */
export interface EligibilityQuoteRequest {
  memberNumber: string;
  serviceCategory: string;
  tariffCodes: string[];
  billedAmount: string;
  currencyCode: string;
  /** ISO date (YYYY-MM-DD). */
  dateOfService: string;
}

/**
 * Response from {@code POST /api/v1/eligibility-quote}. Every monetary
 * field is a decimal string in the request's currency code. Client
 * renders these verbatim — no math, no aggregation
 * (per {@code feedback_stats_serverside}).
 */
export interface EligibilityQuoteResponse {
  coverage: 'ACTIVE' | 'TERMINATED' | 'IN_ARREARS' | 'SUSPENDED' | 'INELIGIBLE' | 'UNKNOWN';
  networkTier: string | null;
  deductibleRemaining: string | null;
  estimatedAllowed: string;
  estimatedCopay: string;
  estimatedCoinsurance: string;
  estimatedShortfall: string;
  estimatedPatientResponsibility: string;
  estimatedPlanPaid: string;
  oopMaxRemaining: string | null;
  currencyCode: string;
  notes: string[];
}

@Injectable({ providedIn: 'root' })
export class EligibilityQuoteService {
  constructor(private api: ApiService) {}

  quote(request: EligibilityQuoteRequest): Observable<EligibilityQuoteResponse> {
    return this.api.post<EligibilityQuoteResponse>('/eligibility-quote', request);
  }
}
