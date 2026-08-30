import { Injectable } from '@angular/core';
import { ReportJobPollingService } from './report-job-polling.service';

/**
 * @deprecated since Phase 15 §1 — use {@link ReportJobPollingService}. Kept
 * as a re-export alias during the rename window so existing report pages
 * (IBNR, LOSS, PERSISTENCY, LAPSE, MORTALITY, MORBIDITY) keep working
 * without touching every injection point. Behaviour is identical — both
 * classes hit the canonical {@code /api/v1/reports/jobs/{jobId}} URL.
 * Removed in Phase 15 §23.
 */
@Injectable({ providedIn: 'root' })
export class ActuarialJobPollingService extends ReportJobPollingService {}
