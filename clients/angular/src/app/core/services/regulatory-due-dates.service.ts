import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';

/** Server-computed banner row for one Phase-16 regulator report. */
export interface DueDateBannerRow {
  reportKey: string;
  reportLabel: string;
  cadence: 'MONTHLY' | 'QUARTERLY' | 'ANNUAL' | 'EVENT_DRIVEN';
  periodStart: string;
  periodEnd: string;
  dueDate: string;
  daysUntilDue: number;
  submissionStatus: 'PENDING' | 'SUBMITTED' | 'AMENDED' | 'SUPERSEDED';
  severity: 'INFO' | 'AMBER' | 'RED';
}

/**
 * Phase 16 §0 REG14 client for the reports-hub due-date banner. One GET
 * returns every applicable Phase-16 report; the Angular hub renders a
 * banner per report card.
 */
@Injectable({ providedIn: 'root' })
export class RegulatoryDueDatesService {
  constructor(private api: ApiService) {}

  list(): Observable<DueDateBannerRow[]> {
    return this.api.get<DueDateBannerRow[]>('/reports/regulatory/due-dates');
  }
}
