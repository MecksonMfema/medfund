import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  TenantRaConfigRow,
  TenantRaConfigService,
  AddTenantRaConfig,
  UpdateTenantRaConfig,
} from '../../../../core/services/tenant-ra-config.service';
import { Ifrs17Portfolio, Ifrs17PortfolioService } from '../../../../core/services/ifrs17-portfolio.service';
import { TenantService } from '../../../../core/services/tenant.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';

interface EditableRow extends TenantRaConfigRow {
  editing?: boolean;
  draftParam?: string;
  draftSourceNote?: string;
  draftEffectiveTo?: string | null;
}

/**
 * Per-portfolio Risk Adjustment methodology CRUD tab. The portfolio picker
 * is a name-labelled dropdown backed by
 * {@link Ifrs17PortfolioService#list} — never a raw UUID input per
 * {@code feedback_no_raw_id_inputs}. Methodology selection toggles which
 * numeric parameter (cocRate or targetConfidenceLevel) is required and
 * displayed; the row-level CHECK constraint on the server ultimately
 * enforces this, but a client-side toggle keeps the UX obvious.
 */
@Component({
  selector: 'app-ifrs17-ra-config-tab',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, SkeletonComponent, SelectComponent],
  templateUrl: './ra-config-tab.component.html',
  styleUrl: '../actuarial-bases/actuarial-bases.component.scss',
})
export class RaConfigTabComponent implements OnInit {
  rows: EditableRow[] = [];
  portfolios: Ifrs17Portfolio[] = [];
  loading = false;
  loadingPortfolios = false;
  errorMessage: string | null = null;
  successMessage: string | null = null;
  pendingId: string | null = null;

  addingOpen = false;
  adding = false;
  newRow: AddTenantRaConfig = this.blankNewRow();

  readonly methodologyOptions: SelectOption[] = [
    { value: 'COC', label: 'Cost of Capital (CoC)' },
    { value: 'CI',  label: 'Confidence Interval (CI)' },
  ];

  constructor(
    private service: TenantRaConfigService,
    private portfolioService: Ifrs17PortfolioService,
    private tenantService: TenantService,
  ) {}

  ngOnInit(): void {
    this.loadPortfolios();
    this.refresh();
  }

  /** Portfolio picker options for both the add form and the list rendering. */
  get portfolioOptions(): SelectOption[] {
    return this.portfolios.map(p => ({ value: p.id, label: p.name }));
  }

  portfolioNameFor(id: string): string {
    return this.portfolios.find(p => p.id === id)?.name ?? id;
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
        this.errorMessage = err?.error?.detail || err?.message || 'Failed to load RA config rows';
        this.loading = false;
      },
    });
  }

  private loadPortfolios(): void {
    this.loadingPortfolios = true;
    this.portfolioService.list(false).subscribe({
      next: (list) => {
        this.portfolios = list;
        this.loadingPortfolios = false;
      },
      error: () => {
        this.loadingPortfolios = false;
        // Non-fatal — the tab still loads; picker just has no options.
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

  onMethodologyChange(m: 'COC' | 'CI'): void {
    this.newRow.methodology = m;
    // Clear the other-methodology param so the request payload is clean.
    if (m === 'COC') this.newRow.targetConfidenceLevel = null;
    if (m === 'CI') this.newRow.cocRate = null;
  }

  add(): void {
    const tenantId = this.tenantService.getTenantId();
    if (!tenantId) return;

    if (!this.newRow.portfolioId) {
      this.errorMessage = 'Pick a portfolio';
      return;
    }
    if (this.newRow.methodology === 'COC' && !this.newRow.cocRate) {
      this.errorMessage = 'CoC methodology requires a cocRate';
      return;
    }
    if (this.newRow.methodology === 'CI' && !this.newRow.targetConfidenceLevel) {
      this.errorMessage = 'CI methodology requires a targetConfidenceLevel';
      return;
    }

    this.adding = true;
    this.errorMessage = null;
    this.service.add(tenantId, this.trimAddPayload(this.newRow)).subscribe({
      next: () => {
        this.adding = false;
        this.addingOpen = false;
        this.successMessage = `Added RA config for ${this.portfolioNameFor(this.newRow.portfolioId)}`;
        setTimeout(() => (this.successMessage = null), 3000);
        this.newRow = this.blankNewRow();
        this.refresh();
      },
      error: (err) => {
        this.adding = false;
        this.errorMessage = err?.error?.detail || 'Failed to add RA config';
      },
    });
  }

  paramLabelFor(row: TenantRaConfigRow): string {
    return row.methodology === 'COC' ? 'CoC rate' : 'Confidence level';
  }

  paramValueFor(row: TenantRaConfigRow): string {
    return row.methodology === 'COC' ? (row.cocRate ?? '-') : (row.targetConfidenceLevel ?? '-');
  }

  startEdit(row: EditableRow): void {
    row.editing = true;
    row.draftParam = row.methodology === 'COC'
      ? (row.cocRate ?? '')
      : (row.targetConfidenceLevel ?? '');
    row.draftSourceNote = row.sourceNote ?? '';
    row.draftEffectiveTo = row.effectiveTo;
  }

  cancelEdit(row: EditableRow): void {
    row.editing = false;
    row.draftParam = undefined;
    row.draftSourceNote = undefined;
    row.draftEffectiveTo = undefined;
  }

  saveEdit(row: EditableRow): void {
    const tenantId = this.tenantService.getTenantId();
    if (!tenantId) return;
    const param = (row.draftParam ?? '').trim();
    const payload: UpdateTenantRaConfig = {
      cocRate: row.methodology === 'COC' ? param : null,
      targetConfidenceLevel: row.methodology === 'CI' ? param : null,
      sourceNote: (row.draftSourceNote ?? '').trim() || null,
      effectiveTo: row.draftEffectiveTo || null,
    };
    this.pendingId = row.id;
    this.service.update(tenantId, row.id, payload).subscribe({
      next: (updated) => {
        Object.assign(row, updated, { editing: false });
        row.draftParam = undefined;
        row.draftSourceNote = undefined;
        row.draftEffectiveTo = undefined;
        this.pendingId = null;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to update RA config';
        this.pendingId = null;
      },
    });
  }

  remove(row: EditableRow): void {
    const name = this.portfolioNameFor(row.portfolioId);
    if (!confirm(`Delete RA config for ${name} (${row.methodology})?`)) return;
    const tenantId = this.tenantService.getTenantId();
    if (!tenantId) return;
    this.pendingId = row.id;
    this.service.delete(tenantId, row.id).subscribe({
      next: () => {
        this.rows = this.rows.filter(r => r.id !== row.id);
        this.pendingId = null;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to delete RA config';
        this.pendingId = null;
      },
    });
  }

  private blankNewRow(): AddTenantRaConfig {
    return {
      portfolioId: '',
      methodology: 'COC',
      cocRate: '',
      targetConfidenceLevel: null,
      sourceNote: '',
      effectiveFrom: null,
      effectiveTo: null,
    };
  }

  private trimAddPayload(p: AddTenantRaConfig): AddTenantRaConfig {
    return {
      portfolioId: p.portfolioId,
      methodology: p.methodology,
      cocRate: p.methodology === 'COC' ? (p.cocRate ?? '').toString().trim() : null,
      targetConfidenceLevel: p.methodology === 'CI'
        ? (p.targetConfidenceLevel ?? '').toString().trim() : null,
      sourceNote: (p.sourceNote ?? '').toString().trim() || null,
      effectiveFrom: p.effectiveFrom || null,
      effectiveTo: p.effectiveTo || null,
    };
  }
}
