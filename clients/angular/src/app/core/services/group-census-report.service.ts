import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';
import { ReportResponse } from './report-envelope';

/**
 * GROUP_CENSUS report data layer. One row per <em>holder</em>: either a
 * corporate group (`holderType === 'GROUP'`, `groupId` is a `groups.id`)
 * or an ungrouped individual policyholder (`holderType === 'INDIVIDUAL'`,
 * `groupId` is the principal member's `members.id`).
 *
 * Principal status counts come from the members table; dependant status
 * counts come from the dependants table. `coveredLives` = `totalMembers`
 * + `totalDependants`.
 */
export interface GroupCensusRow {
  groupId: string;
  holderType: 'GROUP' | 'INDIVIDUAL';
  groupName: string;
  registrationNumber: string | null;
  contactPerson: string | null;
  contactEmail: string | null;
  activeMembers: number;
  suspendedMembers: number;
  lapsedMembers: number;
  terminatedMembers: number;
  totalMembers: number;
  activeDependants: number;
  suspendedDependants: number;
  lapsedDependants: number;
  terminatedDependants: number;
  totalDependants: number;
  coveredLives: number;
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
