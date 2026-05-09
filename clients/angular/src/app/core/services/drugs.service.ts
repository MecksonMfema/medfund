import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';

export type DrugType = 'ACUTE' | 'CHRONIC' | 'OFF_LIMIT';
export type DrugUnit = 'unit' | 'ml' | 'g' | 'mg' | 'tablet' | 'capsule';

export interface Drug {
  id: string;
  drugName: string;
  drugType: DrugType;
  unitOfMeasurement: DrugUnit;
  tariffCode?: string;
  wholesaleCostZwl?: string;
  wholesaleCostUsd?: string;
  paymentPercentage: string;
  doNotPay: boolean;
  isActive: boolean;
  createdAt?: string;
  updatedAt?: string;
}

export interface UpsertDrugPayload {
  drugName: string;
  drugType?: DrugType;
  unitOfMeasurement?: DrugUnit;
  tariffCode?: string;
  wholesaleCostZwl?: string;
  wholesaleCostUsd?: string;
  paymentPercentage?: string;
  doNotPay?: boolean;
  isActive?: boolean;
}

@Injectable({ providedIn: 'root' })
export class DrugsService {
  constructor(private api: ApiService) {}

  list(activeOnly = false): Observable<Drug[]> {
    const params: Record<string, string> = {};
    if (activeOnly) params['activeOnly'] = 'true';
    return this.api.get<Drug[]>('/drugs', params);
  }

  findById(id: string): Observable<Drug> {
    return this.api.get<Drug>(`/drugs/${id}`);
  }

  search(q: string): Observable<Drug[]> {
    return this.api.get<Drug[]>('/drugs/search', { q });
  }

  create(body: UpsertDrugPayload): Observable<Drug> {
    return this.api.post<Drug>('/drugs', body);
  }

  update(id: string, body: UpsertDrugPayload): Observable<Drug> {
    return this.api.put<Drug>(`/drugs/${id}`, body);
  }

  delete(id: string): Observable<void> {
    return this.api.delete<void>(`/drugs/${id}`);
  }
}
