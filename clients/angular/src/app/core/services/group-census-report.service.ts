import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';
import { ReportResponse } from './report-envelope';

/**
 * Phase 13 §C Phase 10 — GROUP_CENSUS report data layer. One row per
 * group at {@code asOf} with per-status member counts.
 */
export interface GroupCensusRow {
  groupId: string;
  groupName: string;
  registrationNumber: string | null;
  contactPerson: string | null;
  contactEmail: string | null;
  activeMembers: number;
  suspendedMembers: number;
  lapsedMembers: number;
  terminatedMembers: number;
  totalMembers: number;
}

export interface GroupCensusResult {
  asOf: string;
  groups: GroupCensusRow[];
}

export interface GroupCensusParams {
  asOf?: string;
  groupId?: string | null;
  status?: string | null;
  reportingCurrency?: string;
}

function censusParams(opts: GroupCensusParams): Record<string, string> {
  const p: Record<string, string> = {};
  if (opts.asOf)              p['asOf']              = opts.asOf;
  if (opts.groupId)           p['groupId']           = opts.groupId;
  if (opts.status)            p['status']            = opts.status;
  if (opts.reportingCurrency) p['reportingCurrency'] = opts.reportingCurrency;
  return p;
}

@Injectable({ providedIn: 'root' })
export class GroupCensusReportService {
  constructor(private api: ApiService) {}

  get(opts: GroupCensusParams): Observable<ReportResponse<GroupCensusResult>> {
    return this.api.get<ReportResponse<GroupCensusResult>>(
      '/reports/policy-lifecycle/group-census', censusParams(opts));
  }

  exportExcel(opts: GroupCensusParams): Observable<Blob> {
    return this.api.getBlob(
      '/reports/policy-lifecycle/group-census/export', censusParams(opts));
  }
}
