import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';

export type FundAssetClass =
  | 'EQUITY'
  | 'FIXED_INCOME'
  | 'MULTI_ASSET'
  | 'MONEY_MARKET'
  | 'REAL_ESTATE'
  | 'OTHER';

export type LedgerTransactionType =
  | 'PURCHASE'
  | 'SALE'
  | 'ROLLOVER'
  | 'FEE_DEDUCTION'
  | 'FUND_SWITCH_IN'
  | 'FUND_SWITCH_OUT';

export interface UnitLinkedFundRow {
  id: string;
  name: string;
  currency: string;
  baseAssetClass: FundAssetClass;
  isActive: boolean;
  createdAt: string | null;
  updatedAt: string | null;
  actorId: string | null;
  actorEmail: string | null;
}

export interface CreateUnitLinkedFund {
  name: string;
  currency: string;
  baseAssetClass: FundAssetClass;
}

export interface UpdateUnitLinkedFund {
  name: string;
  baseAssetClass: FundAssetClass;
  isActive: boolean;
}

export interface FundNavHistoryRow {
  id: string;
  fundId: string;
  valuationDate: string;
  navPerUnit: string;
  source: 'ADMIN' | 'AUTO';
  createdAt: string | null;
  actorId: string | null;
  actorEmail: string | null;
}

export interface CreateFundNavHistory {
  valuationDate: string;
  navPerUnit: string;
}

export interface PolicyUnitLedgerRow {
  id: string;
  policyId: string;
  fundId: string;
  transactionDate: string;
  transactionType: LedgerTransactionType;
  units: string;
  price: string;
  createdAt: string | null;
  actorId: string | null;
  actorEmail: string | null;
}

export interface CreatePolicyUnitLedger {
  policyId: string;
  transactionDate: string;
  transactionType: LedgerTransactionType;
  units: string;
  price: string;
}

export interface VariableFeeScheduleRow {
  id: string;
  fundId: string;
  effectiveFrom: string;
  effectiveTo: string | null;
  feePercentage: string;
  createdAt: string | null;
  actorId: string | null;
  actorEmail: string | null;
}

export interface CreateVariableFeeSchedule {
  effectiveFrom: string;
  effectiveTo: string | null;
  feePercentage: string;
}

export interface UpdateVariableFeeSchedule {
  effectiveTo: string | null;
  feePercentage: string;
}

/**
 * Thin HTTP wrapper for the Phase 15 §8 (I4) VFA admin surfaces: unit-linked
 * fund catalog + NAV history + policy unit ledger + variable fee schedule.
 * Backing user-service endpoints all live under {@code /api/v1/underwriting/*}.
 */
@Injectable({ providedIn: 'root' })
export class UnitLinkedFundService {
  constructor(private api: ApiService) {}

  // ── Fund catalog ────────────────────────────────────────────────────────
  listFunds(): Observable<UnitLinkedFundRow[]> {
    return this.api.get<UnitLinkedFundRow[]>('/underwriting/funds');
  }

  listActiveFunds(): Observable<UnitLinkedFundRow[]> {
    return this.api.get<UnitLinkedFundRow[]>('/underwriting/funds/active');
  }

  addFund(body: CreateUnitLinkedFund): Observable<UnitLinkedFundRow> {
    return this.api.post<UnitLinkedFundRow>('/underwriting/funds', body);
  }

  updateFund(id: string, body: UpdateUnitLinkedFund): Observable<UnitLinkedFundRow> {
    return this.api.put<UnitLinkedFundRow>(`/underwriting/funds/${id}`, body);
  }

  deleteFund(id: string): Observable<void> {
    return this.api.delete<void>(`/underwriting/funds/${id}`);
  }

  // ── NAV history ─────────────────────────────────────────────────────────
  listNavHistory(fundId: string): Observable<FundNavHistoryRow[]> {
    return this.api.get<FundNavHistoryRow[]>(`/underwriting/funds/${fundId}/nav-history`);
  }

  addNavRow(fundId: string, body: CreateFundNavHistory): Observable<FundNavHistoryRow> {
    return this.api.post<FundNavHistoryRow>(`/underwriting/funds/${fundId}/nav-history`, body);
  }

  // ── Variable fee schedule ───────────────────────────────────────────────
  listVariableFees(fundId: string): Observable<VariableFeeScheduleRow[]> {
    return this.api.get<VariableFeeScheduleRow[]>(`/underwriting/funds/${fundId}/variable-fees`);
  }

  addVariableFee(fundId: string, body: CreateVariableFeeSchedule): Observable<VariableFeeScheduleRow> {
    return this.api.post<VariableFeeScheduleRow>(`/underwriting/funds/${fundId}/variable-fees`, body);
  }

  updateVariableFee(id: string, body: UpdateVariableFeeSchedule): Observable<VariableFeeScheduleRow> {
    return this.api.put<VariableFeeScheduleRow>(`/underwriting/variable-fees/${id}`, body);
  }

  deleteVariableFee(id: string): Observable<void> {
    return this.api.delete<void>(`/underwriting/variable-fees/${id}`);
  }

  // ── Policy unit ledger ──────────────────────────────────────────────────
  listLedgerByFund(fundId: string): Observable<PolicyUnitLedgerRow[]> {
    return this.api.get<PolicyUnitLedgerRow[]>(`/underwriting/funds/${fundId}/ledger`);
  }

  listLedgerByPolicy(policyId: string): Observable<PolicyUnitLedgerRow[]> {
    return this.api.get<PolicyUnitLedgerRow[]>(`/underwriting/policies/${policyId}/ledger`);
  }

  appendLedger(fundId: string, body: CreatePolicyUnitLedger): Observable<PolicyUnitLedgerRow> {
    return this.api.post<PolicyUnitLedgerRow>(`/underwriting/funds/${fundId}/ledger`, body);
  }
}
