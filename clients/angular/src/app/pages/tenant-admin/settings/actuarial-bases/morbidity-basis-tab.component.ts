import { Component, ElementRef, OnInit, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  TenantMorbidityBasisRow,
  TenantMorbidityBasisService,
  AddTenantMorbidityBasis,
  UpdateTenantMorbidityBasis,
} from '../../../../core/services/tenant-morbidity-basis.service';
import { TenantService } from '../../../../core/services/tenant.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';
import { INSURANCE_LINES } from '../../../../core/models/insurance-lines';
import { ActuarialBasisTablesService } from '../../../../core/services/actuarial-basis-tables.service';

interface EditableRow extends TenantMorbidityBasisRow {
  editing?: boolean;
  draftBasisName?: string;
  draftMorbidityMultiplier?: string;
  draftEffectiveTo?: string | null;
}

/**
 * Morbidity-basis CRUD table — sibling of {@code MortalityBasisTabComponent}
 * with the morbidity dropdown source instead of mortality. Feeds the
 * <code>MORBIDITY_STUDY</code> report. Phase 6 sources the basis-name
 * dropdown from a live query against ai-service's
 * {@code /api/v1/actuarial/basis-tables/list?category=morbidity} endpoint.
 */
@Component({
  selector: 'app-morbidity-basis-tab',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, SkeletonComponent, SelectComponent],
  templateUrl: './morbidity-basis-tab.component.html',
  styleUrl: './actuarial-bases.component.scss',
})
export class MorbidityBasisTabComponent implements OnInit {
  rows: EditableRow[] = [];
  loading = false;
  errorMessage: string | null = null;
  successMessage: string | null = null;
  pendingId: string | null = null;

  addingOpen = false;
  adding = false;
  newRow: AddTenantMorbidityBasis = this.blankNewRow();

  @ViewChild('addFormRef') addFormRef?: ElementRef<HTMLElement>;

  readonly insuranceLineOptions: SelectOption[] = INSURANCE_LINES.map(l => ({
    value: l.value, label: l.label,
  }));
  basisNameOptions: SelectOption[] = [];
  basisLoading = false;

  constructor(
    private service: TenantMorbidityBasisService,
    private tenantService: TenantService,
    private basisTables: ActuarialBasisTablesService,
  ) {}

  ngOnInit(): void {
    this.loadBasisOptions();
    this.refresh();
  }

  private loadBasisOptions(): void {
    this.basisLoading = true;
    this.basisTables.list('morbidity').subscribe({
      next: (rows) => {
        this.basisNameOptions = rows.map(r => ({ value: r.name, label: r.displayName }));
        this.basisLoading = false;
      },
      error: () => {
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
        this.errorMessage = err?.error?.detail || err?.message || 'Failed to load morbidity basis rows';
        this.loading = false;
      },
    });
  }

  openAdd(): void {
    this.addingOpen = true;
    this.newRow = this.blankNewRow();
    // Scroll the newly rendered form into view on the next tick so the admin
    // never has to hunt for the Add Row inputs on tenants with a long
    // basis-row table above.
    setTimeout(() => {
      const el = this.addFormRef?.nativeElement;
      if (!el) return;
      el.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
      const firstFocusable = el.querySelector<HTMLElement>('button, [role="button"], input, select, textarea');
      firstFocusable?.focus();
    }, 0);
  }

  cancelAdd(): void {
    this.addingOpen = false;
    this.newRow = this.blankNewRow();
  }

  add(): void {
    const tenantId = this.tenantService.getTenantId();
    if (!tenantId) return;
    if (!this.newRow.insuranceLine || !this.newRow.basisName || !this.newRow.morbidityMultiplier) {
      this.errorMessage = 'Line, basis, and multiplier are required';
      return;
    }
    this.adding = true;
    this.errorMessage = null;
    this.service.add(tenantId, this.trimAddPayload(this.newRow)).subscribe({
      next: () => {
        this.adding = false;
        this.addingOpen = false;
        this.successMessage = `Added morbidity basis ${this.newRow.basisName} for ${this.newRow.insuranceLine}`;
        setTimeout(() => (this.successMessage = null), 3000);
        this.newRow = this.blankNewRow();
        this.refresh();
      },
      error: (err) => {
        this.adding = false;
        this.errorMessage = err?.error?.detail || 'Failed to add morbidity basis row';
      },
    });
  }

  startEdit(row: EditableRow): void {
    row.editing = true;
    row.draftBasisName = row.basisName;
    row.draftMorbidityMultiplier = row.morbidityMultiplier;
    row.draftEffectiveTo = row.effectiveTo;
  }

  cancelEdit(row: EditableRow): void {
    row.editing = false;
    row.draftBasisName = undefined;
    row.draftMorbidityMultiplier = undefined;
    row.draftEffectiveTo = undefined;
  }

  saveEdit(row: EditableRow): void {
    const tenantId = this.tenantService.getTenantId();
    if (!tenantId) return;
    const payload: UpdateTenantMorbidityBasis = {
      basisName: (row.draftBasisName ?? '').trim(),
      morbidityMultiplier: (row.draftMorbidityMultiplier ?? '').trim(),
      effectiveTo: row.draftEffectiveTo || null,
    };
    this.pendingId = row.id;
    this.service.update(tenantId, row.id, payload).subscribe({
      next: (updated) => {
        Object.assign(row, updated, { editing: false });
        row.draftBasisName = undefined;
        row.draftMorbidityMultiplier = undefined;
        row.draftEffectiveTo = undefined;
        this.pendingId = null;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to update morbidity basis row';
        this.pendingId = null;
      },
    });
  }

  remove(row: EditableRow): void {
    if (!confirm(`Delete morbidity basis for ${row.insuranceLine} (${row.basisName})?`)) return;
    const tenantId = this.tenantService.getTenantId();
    if (!tenantId) return;
    this.pendingId = row.id;
    this.service.delete(tenantId, row.id).subscribe({
      next: () => {
        this.rows = this.rows.filter(r => r.id !== row.id);
        this.pendingId = null;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to delete morbidity basis row';
        this.pendingId = null;
      },
    });
  }

  private blankNewRow(): AddTenantMorbidityBasis {
    return {
      insuranceLine: '',
      basisName: '',
      morbidityMultiplier: '1.0000',
      effectiveFrom: null,
      effectiveTo: null,
    };
  }

  private trimAddPayload(p: AddTenantMorbidityBasis): AddTenantMorbidityBasis {
    return {
      insuranceLine: p.insuranceLine,
      basisName: p.basisName,
      morbidityMultiplier: (p.morbidityMultiplier ?? '').toString().trim(),
      effectiveFrom: p.effectiveFrom || null,
      effectiveTo: p.effectiveTo || null,
    };
  }
}
