import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';

export type ExpenseType =
  | 'ACQUISITION'
  | 'MAINTENANCE'
  | 'CLAIMS_HANDLING'
  | 'OVERHEAD'
  | 'OTHER';

export interface TenantExpenseAssumptionRow {
  id: string;
  tenantId: string;
  insuranceLine: string;
  expenseType: ExpenseType;
  amountPerPolicy: string;
  currency: string;
  sourceNote: string | null;
  effectiveFrom: string;
  effectiveTo: string | null;
  updatedAt: string | null;
  updatedByEmail: string | null;
}

export interface AddTenantExpenseAssumption {
  insuranceLine: string;
  expenseType: ExpenseType;
  amountPerPolicy: string;
  currency: string;
  sourceNote?: string | null;
  effectiveFrom?: string | null;
  effectiveTo?: string | null;
}

export interface UpdateTenantExpenseAssumption {
  amountPerPolicy: string;
  sourceNote?: string | null;
  effectiveTo?: string | null;
}

/**
 * Thin HTTP wrapper for the tenancy-service IFRS 17 expense assumption
 * CRUD endpoints shipped in Phase 15 §2. Per-line, per-expense-type unit
 * costs feeding the GMM fulfilment cash-flow projection; amounts are
 * per-policy in the assumption's own currency and get converted to the
 * reporting currency by the compute path.
 */
@Injectable({ providedIn: 'root' })
export class TenantExpenseAssumptionService {
  constructor(private api: ApiService) {}

  list(tenantId: string): Observable<TenantExpenseAssumptionRow[]> {
    return this.api.get<TenantExpenseAssumptionRow[]>(
      `/tenants/${tenantId}/ifrs17-expense-assumptions`,
    );
  }

  add(tenantId: string, body: AddTenantExpenseAssumption): Observable<TenantExpenseAssumptionRow> {
    return this.api.post<TenantExpenseAssumptionRow>(
      `/tenants/${tenantId}/ifrs17-expense-assumptions`, body,
    );
  }

  update(tenantId: string, id: string, body: UpdateTenantExpenseAssumption): Observable<TenantExpenseAssumptionRow> {
    return this.api.put<TenantExpenseAssumptionRow>(
      `/tenants/${tenantId}/ifrs17-expense-assumptions/${id}`, body,
    );
  }

  delete(tenantId: string, id: string): Observable<void> {
    return this.api.delete<void>(
      `/tenants/${tenantId}/ifrs17-expense-assumptions/${id}`,
    );
  }
}
