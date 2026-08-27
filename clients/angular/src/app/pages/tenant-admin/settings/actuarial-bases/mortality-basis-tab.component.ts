import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  TenantMortalityBasisRow,
  TenantMortalityBasisService,
  AddTenantMortalityBasis,
  UpdateTenantMortalityBasis,
} from '../../../../core/services/tenant-mortality-basis.service';
import { TenantService } from '../../../../core/services/tenant.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';
import { INSURANCE_LINES } from '../../../../core/models/insurance-lines';
import { ActuarialBasisTablesService } from '../../../../core/services/actuarial-basis-tables.service';

interface EditableRow extends TenantMortalityBasisRow {
  editing?: boolean;
  draftBasisName?: string;
  draftMortalityMultiplier?: string;
  draftEffectiveTo?: string | null;
}

/**
 * Mortality-basis CRUD table. Phase 6 sources the basis-name dropdown from
 * a live query against ai-service's
 * {@code /api/v1/actuarial/basis-tables/list?category=mortality} endpoint,
 * making the YAML tree under {@code services/python/ai-service/app/actuarial/basis_tables/mortality/}
 * the single source of truth.
 */
@Component({
  selector: 'app-mortality-basis-tab',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, SkeletonComponent, SelectComponent],
  templateUrl: './mortality-basis-tab.component.html',
  styleUrl: './actuarial-bases.component.scss',
})
export class MortalityBasisTabComponent implements OnInit {
  rows: EditableRow[] = [];
  loading = false;
  errorMessage: string | null = null;
  successMessage: string | null = null;
  pendingId: string | null = null;

  addingOpen = false;
  adding = false;
  newRow: AddTenantMortalityBasis = this.blankNewRow();

  readonly insuranceLineOptions: SelectOption[] = INSURANCE_LINES.map(l => ({
    value: l.value, label: l.label,
  }));
  basisNameOptions: SelectOption[] = [];
  basisLoading = false;

  constructor(
    private service: TenantMortalityBasisService,
    private tenantService: TenantService,
    private basisTables: ActuarialBasisTablesService,
  ) {}

  ngOnInit(): void {
    this.loadBasisOptions();
    this.refresh();
  }

  private loadBasisOptions(): void {
    this.basisLoading = true;
    this.basisTables.list('mortality').subscribe({
      next: (rows) => {
        this.basisNameOptions = rows.map(r => ({ value: r.name, label: r.displayName }));
        this.basisLoading = false;
      },
      error: () => {
        // Leave dropdown empty on failure; template surfaces a loading→empty
        // state and the caller can retry via page refresh. Manual entry is
        // still possible because basisName is a free-form string on the
        // wire — the dropdown is a convenience, not a validation gate.
        this.basisNameOptions = [];
        this.basisLoading = false;
      },
    });
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
        this.errorMessage = err?.error?.detail || err?.message || 'Failed to load mortality basis rows';
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
    if (!this.newRow.insuranceLine || !this.newRow.basisName || !this.newRow.mortalityMultiplier) {
      this.errorMessage = 'Line, basis, and multiplier are required';
      return;
    }
    this.adding = true;
    this.errorMessage = null;
    this.service.add(tenantId, this.trimAddPayload(this.newRow)).subscribe({
      next: () => {
        this.adding = false;
        this.addingOpen = false;
        this.successMessage = `Added mortality basis ${this.newRow.basisName} for ${this.newRow.insuranceLine}`;
        setTimeout(() => (this.successMessage = null), 3000);
        this.newRow = this.blankNewRow();
        this.refresh();
      },
      error: (err) => {
        this.adding = false;
        this.errorMessage = err?.error?.detail || 'Failed to add mortality basis row';
      },
    });
  }

  startEdit(row: EditableRow): void {
    row.editing = true;
    row.draftBasisName = row.basisName;
    row.draftMortalityMultiplier = row.mortalityMultiplier;
    row.draftEffectiveTo = row.effectiveTo;
  }

  cancelEdit(row: EditableRow): void {
    row.editing = false;
    row.draftBasisName = undefined;
    row.draftMortalityMultiplier = undefined;
    row.draftEffectiveTo = undefined;
  }

  saveEdit(row: EditableRow): void {
    const tenantId = this.tenantService.getTenantId();
    if (!tenantId) return;
    const payload: UpdateTenantMortalityBasis = {
      basisName: (row.draftBasisName ?? '').trim(),
      mortalityMultiplier: (row.draftMortalityMultiplier ?? '').trim(),
      effectiveTo: row.draftEffectiveTo || null,
    };
    this.pendingId = row.id;
    this.service.update(tenantId, row.id, payload).subscribe({
      next: (updated) => {
        Object.assign(row, updated, { editing: false });
        row.draftBasisName = undefined;
        row.draftMortalityMultiplier = undefined;
        row.draftEffectiveTo = undefined;
        this.pendingId = null;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to update mortality basis row';
        this.pendingId = null;
      },
    });
  }

  remove(row: EditableRow): void {
    if (!confirm(`Delete mortality basis for ${row.insuranceLine} (${row.basisName})?`)) return;
    const tenantId = this.tenantService.getTenantId();
    if (!tenantId) return;
    this.pendingId = row.id;
    this.service.delete(tenantId, row.id).subscribe({
      next: () => {
        this.rows = this.rows.filter(r => r.id !== row.id);
        this.pendingId = null;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to delete mortality basis row';
        this.pendingId = null;
      },
    });
  }

  private blankNewRow(): AddTenantMortalityBasis {
    return {
      insuranceLine: '',
      basisName: '',
      mortalityMultiplier: '1.0000',
      effectiveFrom: null,
      effectiveTo: null,
    };
  }

  private trimAddPayload(p: AddTenantMortalityBasis): AddTenantMortalityBasis {
    return {
      insuranceLine: p.insuranceLine,
      basisName: p.basisName,
      mortalityMultiplier: (p.mortalityMultiplier ?? '').toString().trim(),
      effectiveFrom: p.effectiveFrom || null,
      effectiveTo: p.effectiveTo || null,
    };
  }
}
