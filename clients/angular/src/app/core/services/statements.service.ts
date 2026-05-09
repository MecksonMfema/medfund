import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';

export type StatementTargetType = 'GROUP' | 'MEMBER';

export interface StatementHeader {
  targetType: StatementTargetType;
  targetId: string;
  targetName?: string;
  targetCode?: string;
  periodStart: string;
  periodEnd: string;
  currencyCode: string;
  openingBalance: string;
  closingBalance: string;
  totalCharges: string;
  totalPayments: string;
}

export interface StatementLine {
  date: string;
  type: 'CONTRIBUTION' | 'TRANSACTION' | 'CONTRIBUTION_PAID';
  description: string;
  reference?: string;
  debit?: string;
  credit?: string;
  runningBalance: string;
  sourceId: string;
}

export interface StatementResponse {
  header: StatementHeader;
  lines: StatementLine[];
}

export interface StatementFilters {
  targetType: StatementTargetType;
  targetId: string;
  periodStart: string;
  periodEnd: string;
  currency?: string;
}

@Injectable({ providedIn: 'root' })
export class StatementsService {
  constructor(private api: ApiService) {}

  generate(filters: StatementFilters): Observable<StatementResponse> {
    return this.api.get<StatementResponse>('/statements', this.toParams(filters));
  }

  exportExcel(filters: StatementFilters): Observable<Blob> {
    return this.api.getBlob('/statements/export/excel', this.toParams(filters));
  }

  private toParams(filters: StatementFilters): Record<string, string> {
    const params: Record<string, string> = {
      targetType: filters.targetType,
      targetId: filters.targetId,
      periodStart: filters.periodStart,
      periodEnd: filters.periodEnd,
    };
    if (filters.currency) params['currency'] = filters.currency;
    return params;
  }
}
