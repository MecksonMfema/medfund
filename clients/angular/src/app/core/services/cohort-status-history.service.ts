import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';

export type CohortTransitionSource = 'AUTO' | 'MANUAL';
export type CohortTransitionReason =
  | 'AUTO_TEST_FAILED'
  | 'AUTO_TEST_RECOVERED'
  | 'MANUAL_OVERRIDE'
  | 'INITIAL_CLASSIFICATION';

export interface CohortStatusHistoryRow {
  id: string;
  cohortId: string;
  fromStatus: string;
  toStatus: string;
  transitionReason: CohortTransitionReason;
  transitionSource: CohortTransitionSource;
  sourceRunId: string | null;
  effectiveAt: string;
  reasonNote: string | null;
  actorId: string | null;
  actorEmail: string | null;
  createdAt: string;
}

@Injectable({ providedIn: 'root' })
export class CohortStatusHistoryService {
  constructor(private api: ApiService) {}

  listForCohort(cohortId: string): Observable<CohortStatusHistoryRow[]> {
    return this.api.get<CohortStatusHistoryRow[]>(
      `/underwriting/cohorts/${cohortId}/status-history`,
    );
  }
}
