import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  TenantYieldCurveRow,
  TenantYieldCurveService,
  AddTenantYieldCurve,
  UpdateTenantYieldCurve,
} from '../../../../core/services/tenant-yield-curve.service';
import { TenantService } from '../../../../core/services/tenant.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';

interface EditableRow extends TenantYieldCurveRow {
  editing?: boolean;
  draftSpotRate?: string;
  draftEffectiveTo?: string | null;
}

/**
 * Per-tenant yield curve CRUD tab. Rows are grouped visually by currency
 * so an admin can review each curve as a whole; the source badge
 * distinguishes ADMIN-uploaded rows from AUTO-fetched ones (RBZ/SARB) and
 * BACKFILL_FALLBACK rows written by the Phase 6 cohort-lock-in task. CSV
 * upload uses the bulk endpoint — per-row failures show without aborting
 * the whole upload.
 */
@Component({
  selector: 'app-ifrs17-yield-curves-tab',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, SkeletonComponent],
  templateUrl: './yield-curves-tab.component.html',
  styleUrl: '../actuarial-bases/actuarial-bases.component.scss',
})
export class YieldCurvesTabComponent implements OnInit {
  rows: EditableRow[] = [];
  loading = false;
  errorMessage: string | null = null;
  successMessage: string | null = null;
  pendingId: string | null = null;
  uploading = false;

  addingOpen = false;
  adding = false;
  newRow: AddTenantYieldCurve = this.blankNewRow();

  constructor(
    private service: TenantYieldCurveService,
    private tenantService: TenantService,
  ) {}

  ngOnInit(): void {
    this.refresh();
  }

  /** Rows grouped by currency for the per-currency table sections. */
  get rowsByCurrency(): Array<{ currency: string; rows: EditableRow[] }> {
    const map = new Map<string, EditableRow[]>();
    for (const r of this.rows) {
      const list = map.get(r.currency) ?? [];
      list.push(r);
      map.set(r.currency, list);
    }
    return [...map.entries()]
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([currency, rows]) => ({ currency, rows }));
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
        this.errorMessage = err?.error?.detail || err?.message || 'Failed to load yield curve rows';
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
    if (!this.newRow.currency || !this.newRow.tenorMonths || !this.newRow.spotRate) {
      this.errorMessage = 'Currency, tenor months, and spot rate are required';
      return;
    }
    this.adding = true;
    this.errorMessage = null;
    this.service.add(tenantId, this.trimAddPayload(this.newRow)).subscribe({
      next: () => {
        this.adding = false;
        this.addingOpen = false;
        this.successMessage = `Added ${this.newRow.currency} @ ${this.newRow.tenorMonths}m`;
        setTimeout(() => (this.successMessage = null), 3000);
        this.newRow = this.blankNewRow();
        this.refresh();
      },
      error: (err) => {
        this.adding = false;
        this.errorMessage = err?.error?.detail || 'Failed to add yield curve row';
      },
    });
  }

  onCsvSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;
    input.value = '';
    const tenantId = this.tenantService.getTenantId();
    if (!tenantId) return;

    this.uploading = true;
    this.errorMessage = null;
    const reader = new FileReader();
    reader.onload = () => {
      try {
        const text = typeof reader.result === 'string' ? reader.result : '';
        const rows = this.parseCsv(text);
        if (rows.length === 0) {
          this.errorMessage = 'CSV is empty or has no valid rows';
          this.uploading = false;
          return;
        }
        this.service.bulkAdd(tenantId, rows).subscribe({
          next: (saved) => {
            this.uploading = false;
            this.successMessage = `Uploaded ${saved.length} yield curve points`;
            setTimeout(() => (this.successMessage = null), 3000);
            this.refresh();
          },
          error: (err) => {
            this.uploading = false;
            this.errorMessage = err?.error?.detail || 'CSV upload failed';
          },
        });
      } catch (e) {
        this.uploading = false;
        this.errorMessage = (e as Error).message;
      }
    };
    reader.readAsText(file);
  }

  /**
   * Parses `currency,tenor_months,spot_rate[,effective_from[,source]]`
   * per line. Skips blank lines and a leading header row if the first
   * cell isn't a 3-letter currency code.
   */
  private parseCsv(text: string): AddTenantYieldCurve[] {
    const out: AddTenantYieldCurve[] = [];
    const lines = text.split(/\r?\n/);
    for (let i = 0; i < lines.length; i++) {
      const line = lines[i].trim();
      if (!line) continue;
      const cells = line.split(',').map(c => c.trim());
      if (i === 0 && !/^[A-Za-z]{3}$/.test(cells[0])) continue; // header
      if (cells.length < 3) {
        throw new Error(`Line ${i + 1}: need at least currency, tenor_months, spot_rate`);
      }
      const [currency, tenor, rate, effectiveFrom, source] = cells;
      out.push({
        currency: currency.toUpperCase(),
        tenorMonths: Number(tenor),
        spotRate: rate,
        source: (source as AddTenantYieldCurve['source']) || 'ADMIN',
        effectiveFrom: effectiveFrom || null,
      });
    }
    return out;
  }

  startEdit(row: EditableRow): void {
    row.editing = true;
    row.draftSpotRate = row.spotRate;
    row.draftEffectiveTo = row.effectiveTo;
  }

  cancelEdit(row: EditableRow): void {
    row.editing = false;
    row.draftSpotRate = undefined;
    row.draftEffectiveTo = undefined;
  }

  saveEdit(row: EditableRow): void {
    const tenantId = this.tenantService.getTenantId();
    if (!tenantId) return;
    const payload: UpdateTenantYieldCurve = {
      spotRate: (row.draftSpotRate ?? '').trim(),
      effectiveTo: row.draftEffectiveTo || null,
    };
    this.pendingId = row.id;
    this.service.update(tenantId, row.id, payload).subscribe({
      next: (updated) => {
        Object.assign(row, updated, { editing: false });
        row.draftSpotRate = undefined;
        row.draftEffectiveTo = undefined;
        this.pendingId = null;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to update yield curve row';
        this.pendingId = null;
      },
    });
  }

  remove(row: EditableRow): void {
    if (!confirm(`Delete ${row.currency} @ ${row.tenorMonths}m from ${row.effectiveFrom}?`)) return;
    const tenantId = this.tenantService.getTenantId();
    if (!tenantId) return;
    this.pendingId = row.id;
    this.service.delete(tenantId, row.id).subscribe({
      next: () => {
        this.rows = this.rows.filter(r => r.id !== row.id);
        this.pendingId = null;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to delete yield curve row';
        this.pendingId = null;
      },
    });
  }

  private blankNewRow(): AddTenantYieldCurve {
    return {
      currency: '',
      tenorMonths: 12,
      spotRate: '',
      source: 'ADMIN',
      effectiveFrom: null,
      effectiveTo: null,
    };
  }

  private trimAddPayload(p: AddTenantYieldCurve): AddTenantYieldCurve {
    return {
      currency: (p.currency ?? '').toString().trim().toUpperCase(),
      tenorMonths: Number(p.tenorMonths),
      spotRate: (p.spotRate ?? '').toString().trim(),
      source: p.source || 'ADMIN',
      effectiveFrom: p.effectiveFrom || null,
      effectiveTo: p.effectiveTo || null,
    };
  }
}
