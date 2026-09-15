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
}
