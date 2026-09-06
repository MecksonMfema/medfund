import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  CreateFundNavHistory,
  CreatePolicyUnitLedger,
  CreateUnitLinkedFund,
  CreateVariableFeeSchedule,
  FundAssetClass,
  FundNavHistoryRow,
  LedgerTransactionType,
  PolicyUnitLedgerRow,
  UnitLinkedFundRow,
  UnitLinkedFundService,
  UpdateUnitLinkedFund,
  UpdateVariableFeeSchedule,
  VariableFeeScheduleRow,
} from '../../../core/services/unit-linked-fund.service';
import { IconComponent } from '../../../shared/components/icon/icon.component';

type DetailTab = 'nav' | 'fees' | 'ledger';

interface FundDraft extends CreateUnitLinkedFund {
  id?: string;
  isActive?: boolean;
}

/**
 * Phase 15 §8 (I4) — tenant-admin CRUD for VFA unit-linked funds. Follows
 * the Phase 4 deviation precedent (in-list expand-panel rather than a
 * separate route): the list stays canonical, and each row can expand into
 * three tabs (NAV history, variable fees, policy allocations) inline. That
 * keeps the underwriting router shallow and matches the cohort-list detail
 * modal shape.
 */
@Component({
  selector: 'app-funds-list',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent],
  templateUrl: './funds-list.component.html',
})
export class FundsListComponent implements OnInit {
  rows: UnitLinkedFundRow[] = [];
  loading = false;
  saving = false;
  errorMessage: string | null = null;
  successMessage: string | null = null;

  showForm = false;
  draft: FundDraft = this.emptyFund();

  expandedFundId: string | null = null;
  activeTab: DetailTab = 'nav';

  navRows: FundNavHistoryRow[] = [];
  feeRows: VariableFeeScheduleRow[] = [];
  ledgerRows: PolicyUnitLedgerRow[] = [];
  detailLoading = false;

  showNavForm = false;
  navDraft: CreateFundNavHistory = { valuationDate: '', navPerUnit: '' };

  showFeeForm = false;
  feeDraft: CreateVariableFeeSchedule & { id?: string } = {
    effectiveFrom: '',
    effectiveTo: null,
    feePercentage: '',
  };

  showLedgerForm = false;
  ledgerDraft: CreatePolicyUnitLedger = {
    policyId: '',
    transactionDate: '',
    transactionType: 'PURCHASE',
    units: '',
    price: '',
  };

  readonly assetClasses: FundAssetClass[] = [
    'EQUITY', 'FIXED_INCOME', 'MULTI_ASSET', 'MONEY_MARKET', 'REAL_ESTATE', 'OTHER',
  ];

  readonly ledgerTypes: LedgerTransactionType[] = [
    'PURCHASE', 'SALE', 'ROLLOVER', 'FEE_DEDUCTION', 'FUND_SWITCH_IN', 'FUND_SWITCH_OUT',
  ];

  constructor(private svc: UnitLinkedFundService) {}

  ngOnInit(): void { this.load(); }

  load(): void {
    this.loading = true;
    this.svc.listFunds().subscribe({
      next: (rows) => { this.rows = rows; this.loading = false; },
      error: (err) => {
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Failed to load funds';
        this.rows = [];
        this.loading = false;
      },
    });
  }

  // ── Fund form ────────────────────────────────────────────────────────────
  startCreate(): void { this.draft = this.emptyFund(); this.showForm = true; }

  startEdit(row: UnitLinkedFundRow): void {
    this.draft = {
      id: row.id,
      name: row.name,
      currency: row.currency,
      baseAssetClass: row.baseAssetClass,
      isActive: row.isActive,
    };
    this.showForm = true;
  }

  cancel(): void { this.showForm = false; this.draft = this.emptyFund(); }

  save(): void {
    if (!this.draft.name.trim() || !this.draft.currency.trim()) {
      this.errorMessage = 'Name and currency are required';
      return;
    }
    this.saving = true;
    this.errorMessage = null;
    this.successMessage = null;

    if (this.draft.id) {
      const body: UpdateUnitLinkedFund = {
        name: this.draft.name.trim(),
        baseAssetClass: this.draft.baseAssetClass,
        isActive: this.draft.isActive ?? true,
      };
      this.svc.updateFund(this.draft.id, body).subscribe({
        next: () => this.onSaveComplete('Fund updated'),
        error: (err) => this.onSaveError(err),
      });
    } else {
      const body: CreateUnitLinkedFund = {
        name: this.draft.name.trim(),
        currency: this.draft.currency.trim().toUpperCase(),
        baseAssetClass: this.draft.baseAssetClass,
      };
      this.svc.addFund(body).subscribe({
        next: () => this.onSaveComplete('Fund created'),
        error: (err) => this.onSaveError(err),
      });
    }
  }

  hardDelete(row: UnitLinkedFundRow): void {
    if (!confirm(`Delete fund "${row.name}"? Rejected if NAV history, ledger rows or fee schedules reference it: deactivate instead in that case.`)) return;
    this.svc.deleteFund(row.id).subscribe({
      next: () => { this.successMessage = 'Fund deleted'; this.load(); },
      error: (err) => {
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Delete failed';
      },
    });
  }

  // ── Expand / tab switch ─────────────────────────────────────────────────
  toggleExpand(row: UnitLinkedFundRow): void {
    if (this.expandedFundId === row.id) {
      this.expandedFundId = null;
      return;
    }
    this.expandedFundId = row.id;
    this.activeTab = 'nav';
    this.loadTab();
  }

  switchTab(tab: DetailTab): void {
    this.activeTab = tab;
    this.loadTab();
  }

  private loadTab(): void {
    if (!this.expandedFundId) return;
    this.detailLoading = true;
    const id = this.expandedFundId;
    if (this.activeTab === 'nav') {
      this.svc.listNavHistory(id).subscribe({
        next: (rows) => { this.navRows = rows; this.detailLoading = false; },
        error: (err) => this.onDetailError(err),
      });
    } else if (this.activeTab === 'fees') {
      this.svc.listVariableFees(id).subscribe({
        next: (rows) => { this.feeRows = rows; this.detailLoading = false; },
        error: (err) => this.onDetailError(err),
      });
    } else {
      this.svc.listLedgerByFund(id).subscribe({
        next: (rows) => { this.ledgerRows = rows; this.detailLoading = false; },
        error: (err) => this.onDetailError(err),
      });
    }
  }

  // ── NAV history form ────────────────────────────────────────────────────
  startAddNav(): void {
    this.navDraft = { valuationDate: new Date().toISOString().slice(0, 10), navPerUnit: '' };
    this.showNavForm = true;
  }

  cancelNav(): void { this.showNavForm = false; }

  submitNav(): void {
    if (!this.expandedFundId) return;
    if (!this.navDraft.valuationDate || !this.navDraft.navPerUnit) {
      this.errorMessage = 'Valuation date and NAV per unit are required';
      return;
    }
    this.saving = true;
    this.svc.addNavRow(this.expandedFundId, this.navDraft).subscribe({
      next: () => {
        this.saving = false;
        this.successMessage = 'NAV row added';
        this.showNavForm = false;
        this.loadTab();
      },
      error: (err) => this.onSaveError(err),
    });
  }

  // ── Variable fee form ──────────────────────────────────────────────────
  startAddFee(): void {
    this.feeDraft = {
      effectiveFrom: new Date().toISOString().slice(0, 10),
      effectiveTo: null,
      feePercentage: '',
    };
    this.showFeeForm = true;
  }

  startEditFee(row: VariableFeeScheduleRow): void {
    this.feeDraft = {
      id: row.id,
      effectiveFrom: row.effectiveFrom,
      effectiveTo: row.effectiveTo,
      feePercentage: row.feePercentage,
    };
    this.showFeeForm = true;
  }

  cancelFee(): void { this.showFeeForm = false; }

  submitFee(): void {
    if (!this.expandedFundId) return;
    if (!this.feeDraft.effectiveFrom || !this.feeDraft.feePercentage) {
      this.errorMessage = 'Effective from and fee percentage are required';
      return;
    }
    this.saving = true;
    if (this.feeDraft.id) {
      const body: UpdateVariableFeeSchedule = {
        effectiveTo: this.feeDraft.effectiveTo || null,
        feePercentage: this.feeDraft.feePercentage,
      };
      this.svc.updateVariableFee(this.feeDraft.id, body).subscribe({
        next: () => this.onFeeSaved(),
        error: (err) => this.onSaveError(err),
      });
    } else {
      const body: CreateVariableFeeSchedule = {
        effectiveFrom: this.feeDraft.effectiveFrom,
        effectiveTo: this.feeDraft.effectiveTo || null,
        feePercentage: this.feeDraft.feePercentage,
      };
      this.svc.addVariableFee(this.expandedFundId, body).subscribe({
        next: () => this.onFeeSaved(),
        error: (err) => this.onSaveError(err),
      });
    }
  }

  deleteFee(row: VariableFeeScheduleRow): void {
    if (!confirm(`Delete fee schedule row (${row.effectiveFrom} → ${row.effectiveTo ?? '∞'})?`)) return;
    this.svc.deleteVariableFee(row.id).subscribe({
      next: () => { this.successMessage = 'Fee schedule deleted'; this.loadTab(); },
      error: (err) => {
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Delete failed';
      },
    });
  }

  // ── Ledger form ────────────────────────────────────────────────────────
  startAddLedger(): void {
    this.ledgerDraft = {
      policyId: '',
      transactionDate: new Date().toISOString().slice(0, 10),
      transactionType: 'PURCHASE',
      units: '',
      price: '',
    };
    this.showLedgerForm = true;
  }

  cancelLedger(): void { this.showLedgerForm = false; }

  submitLedger(): void {
    if (!this.expandedFundId) return;
    if (!this.ledgerDraft.policyId || !this.ledgerDraft.units || !this.ledgerDraft.price) {
      this.errorMessage = 'Policy ID, units and price are required';
      return;
    }
    this.saving = true;
    this.svc.appendLedger(this.expandedFundId, this.ledgerDraft).subscribe({
      next: () => {
        this.saving = false;
        this.successMessage = 'Ledger row appended';
        this.showLedgerForm = false;
        this.loadTab();
      },
      error: (err) => this.onSaveError(err),
    });
  }

  // ── Helpers ─────────────────────────────────────────────────────────────
  private emptyFund(): FundDraft {
    return { name: '', currency: 'USD', baseAssetClass: 'MULTI_ASSET' };
  }

  private onSaveComplete(msg: string): void {
    this.saving = false;
    this.successMessage = msg;
    this.showForm = false;
    this.load();
  }

  private onFeeSaved(): void {
    this.saving = false;
    this.successMessage = 'Fee schedule saved';
    this.showFeeForm = false;
    this.loadTab();
  }

  private onSaveError(err: any): void {
    this.saving = false;
    this.errorMessage = err?.error?.detail || err?.error?.title || 'Save failed';
  }

  private onDetailError(err: any): void {
    this.detailLoading = false;
    this.errorMessage = err?.error?.detail || err?.error?.title || 'Failed to load details';
  }
}
