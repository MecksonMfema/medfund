import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  TenantPersistencyBasisRow,
  TenantPersistencyBasisService,
  AddTenantPersistencyBasis,
  UpdateTenantPersistencyBasis,
} from '../../../../core/services/tenant-persistency-basis.service';
import { TenantService } from '../../../../core/services/tenant.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';
import { INSURANCE_LINES } from '../../../../core/models/insurance-lines';

interface EditableRow extends TenantPersistencyBasisRow {
  editing?: boolean;
  draftExpectedRetentionPct?: string;
  draftSourceNote?: string;
  draftEffectiveTo?: string | null;
}

/**
 * Persistency-basis CRUD table. One row per (insurance_line, cohort_months,
 * effective_from). The V136 migration seeds an industry-default curve per
 * existing tenant so an admin lands on a working baseline they can tweak.
 */
@Component({
  selector: 'app-persistency-basis-tab',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, SkeletonComponent, SelectComponent],
  templateUrl: './persistency-basis-tab.component.html',
  styleUrl: './actuarial-bases.component.scss',
})
export class PersistencyBasisTabComponent implements OnInit {
  rows: EditableRow[] = [];
  loading = false;
  errorMessage: string | null = null;
  successMessage: string | null = null;
  pendingId: string | null = null;

  addingOpen = false;
  adding = false;
  newRow: AddTenantPersistencyBasis = this.blankNewRow();

  readonly insuranceLineOptions: SelectOption[] = INSURANCE_LINES.map(l => ({
    value: l.value,
    label: l.label,
  }));

  constructor(
    private service: TenantPersistencyBasisService,
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
        this.errorMessage = err?.error?.detail || err?.message || 'Failed to load persistency basis rows';
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
    if (!this.newRow.insuranceLine || !this.newRow.cohortMonths || !this.newRow.expectedRetentionPct) {
      this.errorMessage = 'Line, cohort months, and expected retention are required';
      return;
    }
    this.adding = true;
    this.errorMessage = null;
    this.service.add(tenantId, this.trimAddPayload(this.newRow)).subscribe({
      next: () => {
        this.adding = false;
        this.addingOpen = false;
        this.successMessage = `Added persistency row for ${this.newRow.insuranceLine} @ ${this.newRow.cohortMonths}m`;
        setTimeout(() => (this.successMessage = null), 3000);
        this.newRow = this.blankNewRow();
        this.refresh();
      },
      error: (err) => {
        this.adding = false;
        this.errorMessage = err?.error?.detail || 'Failed to add persistency row';
      },
    });
  }

  startEdit(row: EditableRow): void {
    row.editing = true;
    row.draftExpectedRetentionPct = row.expectedRetentionPct;
    row.draftSourceNote = row.sourceNote ?? '';
    row.draftEffectiveTo = row.effectiveTo;
  }

  cancelEdit(row: EditableRow): void {
    row.editing = false;
    row.draftExpectedRetentionPct = undefined;
    row.draftSourceNote = undefined;
    row.draftEffectiveTo = undefined;
  }

  saveEdit(row: EditableRow): void {
    const tenantId = this.tenantService.getTenantId();
    if (!tenantId) return;
    const payload: UpdateTenantPersistencyBasis = {
      expectedRetentionPct: (row.draftExpectedRetentionPct ?? '').trim(),
      sourceNote: (row.draftSourceNote ?? '').trim() || null,
      effectiveTo: row.draftEffectiveTo || null,
    };
    this.pendingId = row.id;
    this.service.update(tenantId, row.id, payload).subscribe({
      next: (updated) => {
        Object.assign(row, updated, { editing: false });
        row.draftExpectedRetentionPct = undefined;
        row.draftSourceNote = undefined;
        row.draftEffectiveTo = undefined;
        this.pendingId = null;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to update persistency row';
        this.pendingId = null;
      },
    });
  }

  remove(row: EditableRow): void {
    if (!confirm(`Delete persistency row for ${row.insuranceLine} @ ${row.cohortMonths}m?`)) return;
    const tenantId = this.tenantService.getTenantId();
    if (!tenantId) return;
    this.pendingId = row.id;
    this.service.delete(tenantId, row.id).subscribe({
      next: () => {
        this.rows = this.rows.filter(r => r.id !== row.id);
        this.pendingId = null;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to delete persistency row';
        this.pendingId = null;
      },
    });
  }

  private blankNewRow(): AddTenantPersistencyBasis {
    return {
      insuranceLine: '',
      cohortMonths: 12,
      expectedRetentionPct: '',
      sourceNote: '',
      effectiveFrom: null,
      effectiveTo: null,
    };
  }

  private trimAddPayload(p: AddTenantPersistencyBasis): AddTenantPersistencyBasis {
    return {
      insuranceLine: p.insuranceLine,
      cohortMonths: Number(p.cohortMonths),
      expectedRetentionPct: (p.expectedRetentionPct ?? '').toString().trim(),
      sourceNote: (p.sourceNote ?? '').toString().trim() || null,
      effectiveFrom: p.effectiveFrom || null,
      effectiveTo: p.effectiveTo || null,
    };
  }
}
