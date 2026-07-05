import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';

export interface PricingSuggestionRequest {
  schemeId: string;
  dateOfBirth: string;   // ISO YYYY-MM-DD
  gender?: string;
  hasChronicConditions?: boolean;
  smoker?: boolean;
  bmi?: number;
}

export interface PricingSuggestionResponse {
  suggestedAmount: string;   // BigDecimal serialised as string
  currencyCode: string;
  rationale: string;
  factors: string[];
  stub: boolean;
}

/**
 * Talks to the enrolment-time AI pricing-suggestion endpoint on user-
 * service. Currently backed by a hand-rolled stub that layers risk
 * signals over the scheme's age-band guideline; the production model
 * (ai-service) will swap in without changing this contract, so the
 * "Suggest with AI" button on the Custom-premium field won't need re-
 * wiring.
 */
@Injectable({ providedIn: 'root' })
export class PricingSuggestionService {
  constructor(private api: ApiService) {}

  suggest(req: PricingSuggestionRequest): Observable<PricingSuggestionResponse> {
    return this.api.post<PricingSuggestionResponse>('/pricing-suggestions', req);
  }
}
