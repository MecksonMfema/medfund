import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  TenantExpenseAssumptionRow,
  TenantExpenseAssumptionService,
  AddTenantExpenseAssumption,
  UpdateTenantExpenseAssumption,
  ExpenseType,
} from '../../../../core/services/tenant-expense-assumption.service';
import { TenantService } from '../../../../core/services/tenant.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';
import { INSURANCE_LINES } from '../../../../core/models/insurance-lines';

interface EditableRow extends TenantExpenseAssumptionRow {
  editing?: boolean;
  draftAmount?: string;
  draftSourceNote?: string;
  draftEffectiveTo?: string | null;
}

const EXPENSE_TYPES: SelectOption[] = [
  { value: 'ACQUISITION',     label: 'Acquisition' },
  { value: 'MAINTENANCE',     label: 'Maintenance' },
  { value: 'CLAIMS_HANDLING', label: 'Claims handling' },
  { value: 'OVERHEAD',        label: 'Overhead' },
  { value: 'OTHER',           label: 'Other' },
];

/**
 * Per-tenant expense assumption CRUD tab. Rows key on
 * (insurance_line, expense_type, currency, effective_from); the compute
 * path applies these as per-policy unit costs during GMM cash-flow
 * projection. Amounts stay in the assumption's own currency — the
 * projection converts on the way to the reporting currency.
 */
@Component({
  selector: 'app-ifrs17-expense-assumptions-tab',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, SkeletonComponent, SelectComponent],
  templateUrl: './expense-assumptions-tab.component.html',
  styleUrl: '../actuarial-bases/actuarial-bases.component.scss',
})
export class ExpenseAssumptionsTabComponent implements OnInit {
  rows: EditableRow[] = [];
  loading = false;
  errorMessage: string | null = null;
  successMessage: string | null = null;
  pendingId: string | null = null;

  addingOpen = false;
  adding = false;
  newRow: AddTenantExpenseAssumption = this.blankNewRow();

  readonly insuranceLineOptions: SelectOption[] = INSURANCE_LINES.map(l => ({
    value: l.value,
    label: l.label,
  }));
  readonly expenseTypeOptions = EXPENSE_TYPES;

  constructor(
    private service: TenantExpenseAssumptionService,
    private tenantService: TenantService,
  ) {}

  ngOnInit(): void {
    this.refresh();
  }

  refresh(): void {
    const tenantId = this.tenantService.getTenantId();
    if (!tenantId) {
      this.errorMessage = 'No active tenant context';
      return;
    }
    this.loading = true;
    this.errorMessage = null;
    this.service.list(tenantId).subscribe({
      next: (rows) => {
        this.rows = rows.map(r => ({ ...r }));
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || err?.message || 'Failed to load expense assumption rows';
        this.loading = false;
      },
    });
  }

  openAdd(): void {
    this.addingOpen = true;
    this.newRow = this.blankNewRow();
  }

  cancelAdd(): void {
    this.addingOpen = false;
    this.newRow = this.blankNewRow();
  }

  add(): void {
    const tenantId = this.tenantService.getTenantId();
    if (!tenantId) return;
    if (!this.newRow.insuranceLine || !this.newRow.expenseType
        || !this.newRow.amountPerPolicy || !this.newRow.currency) {
      this.errorMessage = 'Line, expense type, amount, and currency are required';
      return;
    }
    this.adding = true;
    this.errorMessage = null;
    this.service.add(tenantId, this.trimAddPayload(this.newRow)).subscribe({
      next: () => {
        this.adding = false;
        this.addingOpen = false;
        this.successMessage =
          `Added ${this.newRow.insuranceLine} / ${this.newRow.expenseType} ${this.newRow.currency}`;
        setTimeout(() => (this.successMessage = null), 3000);
        this.newRow = this.blankNewRow();
        this.refresh();
      },
      error: (err) => {
        this.adding = false;
        this.errorMessage = err?.error?.detail || 'Failed to add expense assumption row';
      },
    });
  }

  startEdit(row: EditableRow): void {
    row.editing = true;
    row.draftAmount = row.amountPerPolicy;
    row.draftSourceNote = row.sourceNote ?? '';
    row.draftEffectiveTo = row.effectiveTo;
  }

  cancelEdit(row: EditableRow): void {
    row.editing = false;
    row.draftAmount = undefined;
    row.draftSourceNote = undefined;
    row.draftEffectiveTo = undefined;
  }

  saveEdit(row: EditableRow): void {
    const tenantId = this.tenantService.getTenantId();
    if (!tenantId) return;
    const payload: UpdateTenantExpenseAssumption = {
      amountPerPolicy: (row.draftAmount ?? '').trim(),
      sourceNote: (row.draftSourceNote ?? '').trim() || null,
      effectiveTo: row.draftEffectiveTo || null,
    };
    this.pendingId = row.id;
    this.service.update(tenantId, row.id, payload).subscribe({
      next: (updated) => {
        Object.assign(row, updated, { editing: false });
        row.draftAmount = undefined;
        row.draftSourceNote = undefined;
        row.draftEffectiveTo = undefined;
        this.pendingId = null;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to update expense assumption row';
        this.pendingId = null;
      },
    });
  }

  remove(row: EditableRow): void {
    if (!confirm(`Delete expense assumption for ${row.insuranceLine} / ${row.expenseType}?`)) return;
    const tenantId = this.tenantService.getTenantId();
    if (!tenantId) return;
    this.pendingId = row.id;
    this.service.delete(tenantId, row.id).subscribe({
      next: () => {
        this.rows = this.rows.filter(r => r.id !== row.id);
        this.pendingId = null;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to delete expense assumption row';
        this.pendingId = null;
      },
    });
  }

  private blankNewRow(): AddTenantExpenseAssumption {
    return {
      insuranceLine: '',
      expenseType: 'MAINTENANCE',
      amountPerPolicy: '',
      currency: 'USD',
      sourceNote: '',
      effectiveFrom: null,
      effectiveTo: null,
    };
  }

  private trimAddPayload(p: AddTenantExpenseAssumption): AddTenantExpenseAssumption {
    return {
      insuranceLine: p.insuranceLine,
      expenseType: p.expenseType as ExpenseType,
      amountPerPolicy: (p.amountPerPolicy ?? '').toString().trim(),
      currency: (p.currency ?? '').toString().trim().toUpperCase(),
      sourceNote: (p.sourceNote ?? '').toString().trim() || null,
      effectiveFrom: p.effectiveFrom || null,
      effectiveTo: p.effectiveTo || null,
    };
  }
}
