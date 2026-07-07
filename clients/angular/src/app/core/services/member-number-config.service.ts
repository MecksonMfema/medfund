import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';

/**
 * Snapshot of a tenant's member-number issuance config — V120 scheme
 * (INDEPENDENT vs SHARED_WITH_SUFFIX) plus the V126 shape knobs
 * (prefixes, random-length, suffix separator/padding/start).
 */
export interface MemberNumberConfig {
  tenantId: string;
  memberNumberScheme: string;
  memberNumberPrefix: string;
  dependantNumberPrefix: string;
  memberNumberRandomLength: number;
  memberNumberSuffixSeparator: string;
  memberNumberSuffixPadding: number;
  memberNumberSuffixStart: number;
}

@Injectable({ providedIn: 'root' })
export class MemberNumberConfigService {
  constructor(private api: ApiService) {}

  get(tenantId: string): Observable<MemberNumberConfig> {
    return this.api.get<MemberNumberConfig>(
      `/tenants/${tenantId}/member-number-config`);
  }

  /** PUT the full config back — the backend validates every field against
   *  the V126 CHECK bounds. Partial updates are not supported. */
  update(tenantId: string, config: Omit<MemberNumberConfig, 'tenantId'>): Observable<MemberNumberConfig> {
    return this.api.put<MemberNumberConfig>(
      `/tenants/${tenantId}/member-number-config`, config);
  }
}
