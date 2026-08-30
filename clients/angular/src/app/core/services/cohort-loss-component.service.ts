import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';

export type LossComponentMovementType =
  | 'INITIAL_RECOGNITION'
  | 'RELEASE'
  | 'REVERSAL'
  | 'RECLASSIFICATION_TO_NON_ONEROUS';

export interface CohortLossComponentRow {
  id: string;
  cohortId: string;
  effectiveAt: string;
  movementType: LossComponentMovementType;
  amount: string;
  currency: string;
  sourceRunId: string | null;
  reasonNote: string | null;
  actorId: string | null;
  actorEmail: string | null;
  createdAt: string;
}

export interface CohortLossComponentBalance {
  cohortId: string;
  currency: string;
  balance: string;
}

export interface RecordLossComponentMovementPayload {
  movementType: LossComponentMovementType;
  amount: string;
  currency: string;
  reasonNote?: string;
}

@Injectable({ providedIn: 'root' })
export class CohortLossComponentService {
  constructor(private api: ApiService) {}

  listForCohort(cohortId: string): Observable<CohortLossComponentRow[]> {
    return this.api.get<CohortLossComponentRow[]>(
      `/underwriting/cohorts/${cohortId}/loss-component`,
    );
  }

  balance(cohortId: string, currency: string): Observable<CohortLossComponentBalance> {
    return this.api.get<CohortLossComponentBalance>(
      `/underwriting/cohorts/${cohortId}/loss-component/balance?currency=${encodeURIComponent(currency)}`,
    );
  }

  record(
    cohortId: string,
    payload: RecordLossComponentMovementPayload,
  ): Observable<CohortLossComponentRow> {
    return this.api.post<CohortLossComponentRow>(
      `/underwriting/cohorts/${cohortId}/loss-component`,
      payload,
    );
  }
}
