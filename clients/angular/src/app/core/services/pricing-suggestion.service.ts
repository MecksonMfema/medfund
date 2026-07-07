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
  /**
   * V050 escape-hatch for the "grandfathered continuous member" case
   * (see feedback_effective_date_snap / plan Layer 5): number of full years
   * the member has been continuously enrolled with the tenant. Feeds the
   * AI as a risk-reducing feature so long-service seniors can land on a
   * lower premium despite an age loading. Compute at enrolment time as
   * `today − enrollmentDate` in years; on the add-member form pass 0.
   */
  tenureYears?: number;
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
