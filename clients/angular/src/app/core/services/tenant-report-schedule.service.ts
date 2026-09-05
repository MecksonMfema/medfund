import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';

/**
 * Phase 17 §C.1 — client for the tenant-admin report-schedules surface.
 * Mirrors two backends: tenancy-service owns schedule + recipient CRUD;
 * finance-service owns run history + rerun + in-app download.
 */

export type ReportCadence = 'WEEKLY' | 'MONTHLY' | 'QUARTERLY' | 'ANNUAL';
export type RunStatus =
  | 'requested'
  | 'processing'
  | 'completed'
  | 'failed'
  | string;

export interface TenantReportScheduleRecipient {
  id: string;
  scheduleId: string;
  email: string;
  displayName: string | null;
  isActive: boolean;
  unsubscribeToken: string;
  createdAt: string;
  updatedAt: string;
}

export interface TenantReportScheduleRow {
  id: string;
  tenantId: string;
  reportKey: string;
  reportLabel: string;
  enabled: boolean;
  cadence: ReportCadence;
  hourOfDay: number;
  dayOfWeek: number | null;
  dayOfMonth: number | null;
  reportingCurrency: string | null;
  /**
   * Phase 19 §B Phase 12 — per-key opt-ins. For FRAUD_SIU_REPORT:
   * `{ includeSensitiveSheets: boolean }` (default `{}`).
   */
  params: Record<string, unknown>;
  lastFiredAt: string | null;
  lastStatus: string | null;
  createdAt: string;
  updatedAt: string;
  recipients: TenantReportScheduleRecipient[];
}

export interface CreateTenantReportScheduleRequest {
  reportKey: string;
  enabled: boolean;
  cadence: ReportCadence;
  hourOfDay: number;
  dayOfWeek?: number | null;
  dayOfMonth?: number | null;
  reportingCurrency?: string | null;
  params?: Record<string, unknown>;
}

export interface UpdateTenantReportScheduleRequest {
  enabled?: boolean | null;
  cadence?: ReportCadence | null;
  hourOfDay?: number | null;
  dayOfWeek?: number | null;
  dayOfMonth?: number | null;
  reportingCurrency?: string | null;
  params?: Record<string, unknown>;
}

export interface AddRecipientRequest {
  email: string;
  displayName?: string | null;
  isActive?: boolean | null;
}

export interface UpdateRecipientRequest {
  displayName?: string | null;
  isActive?: boolean | null;
}

export interface ScheduleRunRow {
  jobId: string;
  scheduleId: string;
  reportKey: string;
  status: RunStatus;
  errorMessage: string | null;
  periodStart: string | null;
  periodEnd: string | null;
  requestedAt: string;
  completedAt: string | null;
  hasXlsx: boolean;
}

/**
 * Same shape as the finance rerun endpoint's ReportJob response — polymorphic
 * blob, only jobId/status are guaranteed. Extract narrowly at call sites.
 */
export interface ReportJobRow {
  jobId: string;
  status: string;
  [key: string]: unknown;
}

@Injectable({ providedIn: 'root' })
export class TenantReportScheduleService {
  constructor(private api: ApiService) {}

  list(tenantId: string): Observable<TenantReportScheduleRow[]> {
    return this.api.get<TenantReportScheduleRow[]>(
      `/tenants/${tenantId}/report-schedules`,
    );
  }

  get(tenantId: string, scheduleId: string): Observable<TenantReportScheduleRow> {
    return this.api.get<TenantReportScheduleRow>(
      `/tenants/${tenantId}/report-schedules/${scheduleId}`,
    );
  }

  create(tenantId: string,
         body: CreateTenantReportScheduleRequest): Observable<TenantReportScheduleRow> {
    return this.api.post<TenantReportScheduleRow>(
      `/tenants/${tenantId}/report-schedules`, body);
  }

  update(tenantId: string, scheduleId: string,
         body: UpdateTenantReportScheduleRequest): Observable<TenantReportScheduleRow> {
    return this.api.put<TenantReportScheduleRow>(
      `/tenants/${tenantId}/report-schedules/${scheduleId}`, body);
  }

  delete(tenantId: string, scheduleId: string): Observable<void> {
    return this.api.delete<void>(
      `/tenants/${tenantId}/report-schedules/${scheduleId}`);
  }

  addRecipient(tenantId: string, scheduleId: string,
               body: AddRecipientRequest): Observable<TenantReportScheduleRecipient> {
    return this.api.post<TenantReportScheduleRecipient>(
      `/tenants/${tenantId}/report-schedules/${scheduleId}/recipients`, body);
  }

  updateRecipient(tenantId: string, scheduleId: string, recipientId: string,
                  body: UpdateRecipientRequest): Observable<TenantReportScheduleRecipient> {
    return this.api.put<TenantReportScheduleRecipient>(
      `/tenants/${tenantId}/report-schedules/${scheduleId}/recipients/${recipientId}`, body);
  }

  deleteRecipient(tenantId: string, scheduleId: string,
                  recipientId: string): Observable<void> {
    return this.api.delete<void>(
      `/tenants/${tenantId}/report-schedules/${scheduleId}/recipients/${recipientId}`);
  }

  runHistory(scheduleId: string, limit = 20): Observable<ScheduleRunRow[]> {
    return this.api.get<ScheduleRunRow[]>(
      `/reports/scheduled/schedules/${scheduleId}/runs`,
      { limit: String(limit) });
  }

  rerun(jobId: string): Observable<ReportJobRow> {
    return this.api.post<ReportJobRow>(
      `/reports/scheduled/${jobId}/rerun`, {});
  }

  downloadRun(jobId: string): Observable<Blob> {
    return this.api.getBlob(`/reports/scheduled/runs/${jobId}/download`);
  }
}
