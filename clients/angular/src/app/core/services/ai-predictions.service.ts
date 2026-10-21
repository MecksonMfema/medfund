import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';

export interface AiPredictionRow {
  id: string;
  tenant_id: string;
  insurance_line?: string | null;
  entity_type: string;
  entity_id: string;
  prediction_type: string;
  model_version: string;
  confidence?: number | null;
  accepted?: boolean | null;
  reviewed_by?: string | null;
  reviewed_by_email?: string | null;
  reviewed_at?: string | null;
  created_at?: string | null;
}

export interface AiPredictionDetail extends AiPredictionRow {
  input_features: Record<string, unknown>;
  output: Record<string, unknown>;
}

export interface AiPredictionPage {
  items: AiPredictionRow[];
  total: number;
  page: number;
  size: number;
}

export interface AiPredictionFilters {
  insurance_line?: string;
  entity_type?: string;
  prediction_type?: string;
  model_version?: string;
  accepted?: 'true' | 'false';
  page?: number;
  size?: number;
}

export interface AiReviewQueueBatch {
  items: AiPredictionDetail[];
  total_unreviewed: number;
  high_count: number;
  low_count: number;
  other_count: number;
}

/**
 * One row of the /api/v1/ai/models registry snapshot. Sixteen rows
 * total — 2 model_types × 8 insurance lines.
 */
export interface AiActiveModel {
  model_type: 'fraud' | 'pricing';
  line: string;
  active_version: string | null;
  model_version: string;
  is_fallback: boolean;
  trained_at?: string | null;
  train_samples: number;
  metrics: Record<string, number>;
  schema_status: 'OK' | 'SCHEMA_MISMATCH';
  schema_status_detail?: string | null;
}

export interface AiPromoteResponse {
  model_type: 'fraud' | 'pricing';
  line: string;
  before: string | null;
  after: string;
  audit_event_id: string;
}

/**
 * Wrapper for the AI-service `/api/v1/ai/predictions` endpoints. The
 * gateway attaches X-Tenant-ID + X-Actor-ID + X-Actor-Email on every
 * request; this service only shapes params and body.
 */
@Injectable({ providedIn: 'root' })
export class AiPredictionsService {
  constructor(private api: ApiService) {}

  list(filters: AiPredictionFilters = {}): Observable<AiPredictionPage> {
    const params: Record<string, string> = {};
    if (filters.insurance_line)  params['insurance_line']  = filters.insurance_line;
    if (filters.entity_type)     params['entity_type']     = filters.entity_type;
    if (filters.prediction_type) params['prediction_type'] = filters.prediction_type;
    if (filters.model_version)   params['model_version']   = filters.model_version;
    if (filters.accepted)        params['accepted']        = filters.accepted;
    params['page'] = String(filters.page ?? 0);
    params['size'] = String(filters.size ?? 50);
    return this.api.get<AiPredictionPage>('/ai/predictions', params);
  }

  get(id: string): Observable<AiPredictionDetail> {
    return this.api.get<AiPredictionDetail>(`/ai/predictions/${id}`);
  }

  decide(
    id: string,
    accepted: boolean,
    feedback: string | null = null,
  ): Observable<AiPredictionRow> {
    return this.api.put<AiPredictionRow>(
      `/ai/predictions/${id}/decision`,
      { accepted, feedback },
    );
  }

  /**
   * Stratified 50/50 HIGH/LOW random-sample batch of unreviewed predictions.
   * Backs the Review Queue mode on `/tenant/admin/ai-predictions` (Tranche 1
   * per G1) — training corpora need real true-negative signal, and reviewers
   * only work HIGH by default otherwise.
   */
  reviewQueue(
    modelType: string = 'fraud',
    size: number = 20,
    insuranceLine?: string,
  ): Observable<AiReviewQueueBatch> {
    const params: Record<string, string> = {
      model_type: modelType,
      size: String(size),
    };
    if (insuranceLine) params['insurance_line'] = insuranceLine;
    return this.api.get<AiReviewQueueBatch>('/ai/predictions/review-queue', params);
  }

  /**
   * Snapshot of the AI model registry (Tranche 1 Phase 5) — one row
   * per (model_type, line) tuple with the currently-active version
   * and metadata sidecar values.
   */
  listActiveModels(): Observable<AiActiveModel[]> {
    return this.api.get<AiActiveModel[]>('/ai/models');
  }

  /**
   * Promote a candidate model version. Requires `ai:models:promote`
   * on the caller (enforced server-side); UI hides the button when
   * absent. Emits an audit event with actor + before/after.
   */
  promoteModel(
    modelType: 'fraud' | 'pricing',
    line: string,
    version: string,
  ): Observable<AiPromoteResponse> {
    return this.api.put<AiPromoteResponse>(
      `/ai/models/${modelType}/${encodeURIComponent(line)}/promote`,
      { version },
    );
  }
}
