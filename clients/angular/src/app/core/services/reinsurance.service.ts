import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';
import { ReportResponse } from './report-envelope';

/**
 * Phase 10 reinsurance data layer. Backend lives in finance-service under
 * {@code /api/v1/reinsurance/*}; the gateway proxies both {@code
 * /api/v1/reinsurance/*} and {@code /api/v1/reports/reinsurance/*}.
 */

// ── Reinsurers ──────────────────────────────────────────────────────────────
export interface Reinsurer {
  id: string;
  name: string;
  contactEmail?: string;
  contactAddress?: string;
  jurisdictionCode?: string;
  homeCurrency?: string;
  creditRating?: string;
  active: boolean;
  createdAt?: string;
  updatedAt?: string;
}

export interface CreateReinsurerPayload {
  name: string;
  contactEmail?: string;
  contactAddress?: string;
  jurisdictionCode?: string;
  homeCurrency?: string;
  creditRating?: string;
}

export interface UpdateReinsurerPayload extends CreateReinsurerPayload {
  active: boolean;
}

export interface ReinsurerPage {
  content: Reinsurer[];
  total: number;
  page: number;
  size: number;
  totalPages: number;
}

// ── Treaties ────────────────────────────────────────────────────────────────
export type TreatyType = 'QUOTA_SHARE' | 'SURPLUS_SHARE' | 'EXCESS_OF_LOSS' | 'STOP_LOSS';
export type TreatyStatus = 'DRAFT' | 'ACTIVE' | 'EXPIRED' | 'RENEWED' | 'LAPSED' | 'COMMUTED';

export interface Treaty {
  id: string;
  treatyRef: string;
  treatyType: TreatyType;
  declaredCurrency: string;
  inceptionDate: string;
  expiryDate: string;
  status: TreatyStatus;
  renewedFromTreatyId?: string | null;
  aggregateLimit?: number | null;
  aggregateLimitCurrency?: string | null;
  expectedAnnualPremium?: number | null;
  producerRef?: string | null;
  activatedAt?: string | null;
  createdAt?: string;
  updatedAt?: string;
}

export interface CreateTreatyPayload {
  treatyRef: string;
  treatyType: TreatyType;
  declaredCurrency: string;
  inceptionDate: string;
  expiryDate: string;
  aggregateLimit?: number | null;
  aggregateLimitCurrency?: string | null;
  expectedAnnualPremium?: number | null;
  producerRef?: string | null;
}

export type UpdateTreatyPayload = CreateTreatyPayload;

export interface RenewTreatyPayload {
  treatyRef: string;
  inceptionDate: string;
  expiryDate: string;
  aggregateLimit?: number | null;
  aggregateLimitCurrency?: string | null;
  expectedAnnualPremium?: number | null;
}

export interface TreatyPage {
  content: Treaty[];
  total: number;
  page: number;
  size: number;
  totalPages: number;
}

// ── Layers ──────────────────────────────────────────────────────────────────
export interface TreatyLayer {
  id: string;
  treatyId: string;
  layerOrder: number;
  retention: number;
  layerLimit: number;
  layerCurrency: string;
  rate: number;
  reinstatementCount?: number | null;
  createdAt?: string;
}

export interface UpsertTreatyLayerPayload {
  layerOrder: number;
  retention: number;
  layerLimit: number;
  layerCurrency: string;
  rate: number;
  reinstatementCount?: number | null;
}

// ── Participants ────────────────────────────────────────────────────────────
export interface TreatyParticipant {
  treatyId: string;
  reinsurerId: string;
  reinsurerName: string;
  sharePct: number;
  shareRole: 'LEADER' | 'FOLLOWING';
  createdAt?: string;
}

export interface UpsertTreatyParticipantPayload {
  reinsurerId: string;
  sharePct: number;
  shareRole: 'LEADER' | 'FOLLOWING';
}

// ── Applicable lines ────────────────────────────────────────────────────────
export type InsuranceLine =
  | 'HEALTH' | 'LIFE' | 'FUNERAL' | 'GROUP' | 'TRAVEL' | 'DISABILITY' | 'VEHICLE' | 'PROPERTY';

export interface TreatyApplicableLine {
  treatyId: string;
  insuranceLine: InsuranceLine;
}

// ── Cession rules ───────────────────────────────────────────────────────────
export interface CessionRuleLink {
  id: string;
  treatyId: string;
  ruleDefinitionId: string;
  enabled: boolean;
  createdAt?: string;
  updatedAt?: string;
}

// ── Bordereau reports (Phase 5) ─────────────────────────────────────────────

/** One row per (cession, participant) on the cession bordereau. Native
 *  amounts (R7); participantCeded = nativeCededFull × sharePct / 100. */
export interface CessionBordereauRow {
  cessionId: string;
  treatyId: string;
  treatyRef: string;
  treatyType: TreatyType;
  reinsurerId: string;
  reinsurerName: string;
  sharePct: number;
  shareRole: 'LEADER' | 'FOLLOWING';
  cessionType: 'LOSS' | 'PREMIUM';
  source: 'AUTOMATIC' | 'FACULTATIVE';
  occurredAt: string;
  sourceEventId: string;
  sourceEventType: string;
  nativeBasis: number;
  nativeCededFull: number;
  participantCeded: number;
  currencyCode: string;
  createdAt: string;
  priorPeriodAdjustment: boolean;
}

/** One row per (recovery, participant) on the recoveries bordereau. */
export interface RecoveriesBordereauRow {
  recoveryId: string;
  cessionId: string;
  treatyId: string;
  treatyRef: string;
  reinsurerId: string;
  reinsurerName: string;
  sharePct: number;
  status: 'EXPECTED' | 'INVOICED' | 'RECEIVED' | 'WRITTEN_OFF';
  sourceEventId: string;
  occurredAt: string;
  nativeExpected: number;
  nativeReceived: number | null;
  participantExpected: number;
  participantReceived: number | null;
  currencyCode: string;
  invoicedAt: string | null;
  receivedAt: string | null;
  writeOffReason: string | null;
  createdAt: string;
  priorPeriodAdjustment: boolean;
}

/** One utilization row per (treaty, layer, cededCurrency) since inception. */
export interface TreatyUtilizationRow {
  treatyId: string;
  treatyRef: string;
  treatyType: TreatyType;
  aggregateLimit: number | null;
  aggregateLimitCurrency: string | null;
  inceptionDate: string;
  expiryDate: string;
  layerId: string | null;
  layerOrder: number | null;
  retention: number | null;
  layerLimit: number | null;
  layerCurrency: string | null;
  cededCurrency: string | null;
  totalCededNative: number;
  cessionCount: number;
}

export interface BordereauQuarterParams {
  reinsurerId?: string | null;
  treatyId?: string | null;
  year: number;
  quarter: number;
  reportingCurrency?: string;
}

export interface TreatyUtilizationParams {
  treatyId: string;
  asOfDate?: string;
  reportingCurrency?: string;
}

@Injectable({ providedIn: 'root' })
export class ReinsuranceService {
  constructor(private api: ApiService) {}

  // ── Reinsurers ────────────────────────────────────────────────────────────
  listReinsurers(page = 0, size = 50, active?: boolean): Observable<ReinsurerPage> {
    const params: Record<string, string> = { page: String(page), size: String(size) };
    if (active !== undefined) params['active'] = String(active);
    return this.api.get<ReinsurerPage>('/reinsurance/reinsurers', params);
  }

  getReinsurer(id: string): Observable<Reinsurer> {
    return this.api.get<Reinsurer>(`/reinsurance/reinsurers/${id}`);
  }

  createReinsurer(payload: CreateReinsurerPayload): Observable<Reinsurer> {
    return this.api.post<Reinsurer>('/reinsurance/reinsurers', payload);
  }

  updateReinsurer(id: string, payload: UpdateReinsurerPayload): Observable<Reinsurer> {
    return this.api.put<Reinsurer>(`/reinsurance/reinsurers/${id}`, payload);
  }

  // ── Treaties ──────────────────────────────────────────────────────────────
  listTreaties(page = 0, size = 50, status?: TreatyStatus): Observable<TreatyPage> {
    const params: Record<string, string> = { page: String(page), size: String(size) };
    if (status) params['status'] = status;
    return this.api.get<TreatyPage>('/reinsurance/treaties', params);
  }

  getTreaty(id: string): Observable<Treaty> {
    return this.api.get<Treaty>(`/reinsurance/treaties/${id}`);
  }

  createTreaty(payload: CreateTreatyPayload): Observable<Treaty> {
    return this.api.post<Treaty>('/reinsurance/treaties', payload);
  }

  updateTreaty(id: string, payload: UpdateTreatyPayload): Observable<Treaty> {
    return this.api.put<Treaty>(`/reinsurance/treaties/${id}`, payload);
  }

  activateTreaty(id: string): Observable<Treaty> {
    return this.api.post<Treaty>(`/reinsurance/treaties/${id}/activate`, {});
  }

  voidTreaty(id: string): Observable<Treaty> {
    return this.api.post<Treaty>(`/reinsurance/treaties/${id}/void`, {});
  }

  renewTreaty(id: string, payload: RenewTreatyPayload): Observable<Treaty> {
    return this.api.post<Treaty>(`/reinsurance/treaties/${id}/renew`, payload);
  }

  renewalChain(id: string): Observable<Treaty[]> {
    return this.api.get<Treaty[]>(`/reinsurance/treaties/${id}/renewal-chain`);
  }

  // ── Nested resources ──────────────────────────────────────────────────────
  listLayers(treatyId: string): Observable<TreatyLayer[]> {
    return this.api.get<TreatyLayer[]>(`/reinsurance/treaties/${treatyId}/layers`);
  }

  createLayer(treatyId: string, payload: UpsertTreatyLayerPayload): Observable<TreatyLayer> {
    return this.api.post<TreatyLayer>(`/reinsurance/treaties/${treatyId}/layers`, payload);
  }

  updateLayer(treatyId: string, layerId: string, payload: UpsertTreatyLayerPayload): Observable<TreatyLayer> {
    return this.api.put<TreatyLayer>(`/reinsurance/treaties/${treatyId}/layers/${layerId}`, payload);
  }

  deleteLayer(treatyId: string, layerId: string): Observable<void> {
    return this.api.delete<void>(`/reinsurance/treaties/${treatyId}/layers/${layerId}`);
  }

  listParticipants(treatyId: string): Observable<TreatyParticipant[]> {
    return this.api.get<TreatyParticipant[]>(`/reinsurance/treaties/${treatyId}/participants`);
  }

  upsertParticipant(treatyId: string, payload: UpsertTreatyParticipantPayload): Observable<TreatyParticipant> {
    return this.api.put<TreatyParticipant>(`/reinsurance/treaties/${treatyId}/participants`, payload);
  }

  deleteParticipant(treatyId: string, reinsurerId: string): Observable<void> {
    return this.api.delete<void>(`/reinsurance/treaties/${treatyId}/participants/${reinsurerId}`);
  }

  listApplicableLines(treatyId: string): Observable<TreatyApplicableLine[]> {
    return this.api.get<TreatyApplicableLine[]>(`/reinsurance/treaties/${treatyId}/applicable-lines`);
  }

  addApplicableLine(treatyId: string, insuranceLine: InsuranceLine): Observable<TreatyApplicableLine> {
    return this.api.post<TreatyApplicableLine>(
      `/reinsurance/treaties/${treatyId}/applicable-lines`, { insuranceLine });
  }

  removeApplicableLine(treatyId: string, insuranceLine: InsuranceLine): Observable<void> {
    return this.api.delete<void>(`/reinsurance/treaties/${treatyId}/applicable-lines/${insuranceLine}`);
  }

  listCessionRules(treatyId: string): Observable<CessionRuleLink[]> {
    return this.api.get<CessionRuleLink[]>(`/reinsurance/treaties/${treatyId}/cession-rules`);
  }

  addCessionRule(treatyId: string, ruleDefinitionId: string, enabled = true): Observable<CessionRuleLink> {
    return this.api.post<CessionRuleLink>(
      `/reinsurance/treaties/${treatyId}/cession-rules`, { ruleDefinitionId, enabled });
  }

  toggleCessionRule(treatyId: string, linkId: string, enabled: boolean): Observable<CessionRuleLink> {
    return this.api.put<CessionRuleLink>(
      `/reinsurance/treaties/${treatyId}/cession-rules/${linkId}`, { enabled });
  }

  deleteCessionRule(treatyId: string, linkId: string): Observable<void> {
    return this.api.delete<void>(`/reinsurance/treaties/${treatyId}/cession-rules/${linkId}`);
  }

  // ── Bordereau reports (Phase 5) ────────────────────────────────────────────
  getCessionBordereau(opts: BordereauQuarterParams): Observable<ReportResponse<CessionBordereauRow[]>> {
    return this.api.get<ReportResponse<CessionBordereauRow[]>>(
      '/reports/reinsurance/cession-bordereau', bordereauParams(opts));
  }

  exportCessionBordereauExcel(opts: BordereauQuarterParams): Observable<Blob> {
    return this.api.getBlob('/reports/reinsurance/cession-bordereau/export/excel', bordereauParams(opts));
  }

  getRecoveriesBordereau(opts: BordereauQuarterParams): Observable<ReportResponse<RecoveriesBordereauRow[]>> {
    return this.api.get<ReportResponse<RecoveriesBordereauRow[]>>(
      '/reports/reinsurance/recoveries-bordereau', bordereauParams(opts));
  }

  exportRecoveriesBordereauExcel(opts: BordereauQuarterParams): Observable<Blob> {
    return this.api.getBlob('/reports/reinsurance/recoveries-bordereau/export/excel', bordereauParams(opts));
  }

  getTreatyUtilization(opts: TreatyUtilizationParams): Observable<ReportResponse<TreatyUtilizationRow[]>> {
    return this.api.get<ReportResponse<TreatyUtilizationRow[]>>(
      '/reports/reinsurance/treaty-utilization', utilizationParams(opts));
  }

  exportTreatyUtilizationExcel(opts: TreatyUtilizationParams): Observable<Blob> {
    return this.api.getBlob('/reports/reinsurance/treaty-utilization/export/excel', utilizationParams(opts));
  }
}

function bordereauParams(opts: BordereauQuarterParams): Record<string, string> {
  const params: Record<string, string> = {
    year:    String(opts.year),
    quarter: String(opts.quarter),
  };
  if (opts.reinsurerId)       params['reinsurerId']       = opts.reinsurerId;
  if (opts.treatyId)          params['treatyId']          = opts.treatyId;
  if (opts.reportingCurrency) params['reportingCurrency'] = opts.reportingCurrency;
  return params;
}

function utilizationParams(opts: TreatyUtilizationParams): Record<string, string> {
  const params: Record<string, string> = { treatyId: opts.treatyId };
  if (opts.asOfDate)          params['asOfDate']          = opts.asOfDate;
  if (opts.reportingCurrency) params['reportingCurrency'] = opts.reportingCurrency;
  return params;
}
