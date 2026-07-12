import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  TariffCategoriesService,
  TariffCategory,
  UpsertTariffCategoryPayload,
} from '../../../core/services/tariff-categories.service';
import { IconComponent } from '../../../shared/components/icon/icon.component';
import { SkeletonComponent } from '../../../shared/components/skeleton/skeleton.component';

/**
 * V063 tariff_categories admin — thin list + inline create/edit form.
 * Sits under /tenant/admin/tariff-categories. Sends every write through
 * {@link TariffCategoriesService}. Mirrors the shape of the existing
 * billing-tab benefit-types admin.
 */
interface CategoryDraft extends UpsertTariffCategoryPayload {
  id?: string;
}

@Component({
  selector: 'app-tariff-categories-list',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, SkeletonComponent],
  templateUrl: './tariff-categories-list.component.html',
  styleUrl: './tariff-categories-list.component.scss',
})
export class TariffCategoriesListComponent implements OnInit {
  rows: TariffCategory[] = [];
  loading = false;
  saving = false;
  errorMessage: string | null = null;
  successMessage: string | null = null;

  showForm = false;
  draft: CategoryDraft = this.empty();

  constructor(private svc: TariffCategoriesService) {}

  ngOnInit(): void {
    this.reload();
  }

  reload(): void {
    this.loading = true;
    this.svc.list(false).subscribe({
      next: rows => { this.rows = rows; this.loading = false; },
      error: err => {
        this.errorMessage = err?.error?.detail || 'Failed to load categories';
        this.loading = false;
      },
    });
  }

  startCreate(): void {
    this.draft = this.empty();
    this.showForm = true;
  }

  startEdit(row: TariffCategory): void {
    this.draft = {
      id: row.id,
      code: row.code,
      label: row.label,
      description: row.description,
      isCapOnly: row.isCapOnly,
      isActive: row.isActive,
      sortOrder: row.sortOrder,
    };
    this.showForm = true;
  }

  cancel(): void {
    this.showForm = false;
    this.draft = this.empty();
  }

  save(): void {
    if (!this.draft.code.trim() || !this.draft.label.trim()) {
      this.errorMessage = 'Code and label are required';
      return;
    }
    this.saving = true;
    this.errorMessage = null;
    this.successMessage = null;
    const payload: UpsertTariffCategoryPayload = {
      code: this.draft.code.trim(),
      label: this.draft.label.trim(),
      description: this.draft.description?.trim() || undefined,
      isCapOnly: this.draft.isCapOnly,
      isActive: this.draft.isActive,
      sortOrder: this.draft.sortOrder,
    };
    const stream = this.draft.id
      ? this.svc.update(this.draft.id, payload)
      : this.svc.create(payload);
    stream.subscribe({
      next: () => {
        this.saving = false;
        this.successMessage = 'Category saved';
        this.showForm = false;
        this.reload();
      },
      error: (err) => {
        this.saving = false;
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Save failed';
      },
    });
  }

  deactivate(row: TariffCategory): void {
    if (!confirm(`Deactivate "${row.label}"? Tariffs and benefits still referencing it stay linked.`)) return;
    this.svc.deactivate(row.id).subscribe({
      next: () => {
        this.successMessage = 'Category deactivated';
        this.reload();
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Deactivate failed';
      },
    });
  }

  private empty(): CategoryDraft {
    return {
      code: '',
      label: '',
      description: '',
      isCapOnly: false,
      isActive: true,
      sortOrder: 0,
    };
  }
}
