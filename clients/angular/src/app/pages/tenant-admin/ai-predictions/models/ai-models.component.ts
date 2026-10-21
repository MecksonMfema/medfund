import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import {
  AiActiveModel,
  AiPredictionsService,
} from '../../../../core/services/ai-predictions.service';
import { PermissionService } from '../../../../core/security/permission.service';
import { ToastService } from '../../../../shared/components/toast/toast.service';

/**
 * Tenant-admin snapshot of the AI model registry (Tranche 1 Phase 5).
 *
 * Renders one row per (model_type, line) tuple showing the currently-
 * active version + eval metrics from the metadata sidecar, and a red
 * SCHEMA_MISMATCH badge when the promoted artifact's canonical-features
 * schema is stale (G7 — runtime silently reverted to canonical).
 *
 * Callers holding `ai:models:promote` see a Promote button per row; the
 * confirmation modal ({@code showPromote}) captures the target version
 * and posts to PUT /api/v1/ai/models/{type}/{line}/promote.
 */
@Component({
  selector: 'app-ai-models',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './ai-models.component.html',
  styleUrl: './ai-models.component.scss',
})
export class AiModelsComponent implements OnInit {
  models: AiActiveModel[] = [];
  loading = false;
  loadError = '';

  // Promote modal state
  showPromote = false;
  promoteRow: AiActiveModel | null = null;
  promoteVersionInput = '';
  promoteSubmitting = false;
  promoteError = '';

  constructor(
    private api: AiPredictionsService,
    private permissions: PermissionService,
    private toast: ToastService,
  ) {}

  ngOnInit(): void {
    this.reload();
  }

  reload(): void {
    this.loading = true;
    this.loadError = '';
    this.api.listActiveModels().subscribe({
      next: (rows) => {
        // Sort by (model_type, line) so the layout is stable across reloads.
        this.models = [...rows].sort((a, b) => {
          if (a.model_type !== b.model_type) {
            return a.model_type < b.model_type ? -1 : 1;
          }
          return a.line < b.line ? -1 : a.line > b.line ? 1 : 0;
        });
        this.loading = false;
      },
      error: (err) => {
        this.loadError = err?.message || 'Failed to load AI models';
        this.loading = false;
      },
    });
  }

  canPromote(): boolean {
    return this.permissions.has('ai:models:promote');
  }

  openPromote(row: AiActiveModel): void {
    this.promoteRow = row;
    this.promoteVersionInput = row.active_version ?? '';
    this.promoteError = '';
    this.showPromote = true;
  }

  cancelPromote(): void {
    this.showPromote = false;
    this.promoteRow = null;
    this.promoteVersionInput = '';
    this.promoteError = '';
    this.promoteSubmitting = false;
  }

  confirmPromote(): void {
    if (!this.promoteRow) return;
    const version = this.promoteVersionInput.trim();
    if (!version) {
      this.promoteError = 'Version is required (e.g. v2).';
      return;
    }
    const row = this.promoteRow;
    this.promoteSubmitting = true;
    this.promoteError = '';
    this.api.promoteModel(row.model_type, row.line, version).subscribe({
      next: (resp) => {
        this.toast.success(
          `${row.model_type} model for ${row.line} promoted to ${resp.after}`,
        );
        this.showPromote = false;
        this.promoteRow = null;
        this.promoteVersionInput = '';
        this.promoteSubmitting = false;
        this.reload();
      },
      error: (err) => {
        this.promoteError =
          err?.error?.detail || err?.message || 'Promotion failed';
        this.promoteSubmitting = false;
      },
    });
  }

  primaryMetric(row: AiActiveModel): string {
    // Fraud primary metric is AUC; pricing (once trained) would be R².
    const key = row.model_type === 'fraud' ? 'auc' : 'r2';
    const value = row.metrics?.[key];
    return typeof value === 'number' && Number.isFinite(value)
      ? value.toFixed(3) : '—';
  }

  formattedTrainedAt(row: AiActiveModel): string {
    return row.trained_at ? new Date(row.trained_at).toISOString().slice(0, 10) : '—';
  }

  statusBadgeClass(row: AiActiveModel): string {
    if (row.schema_status === 'SCHEMA_MISMATCH') return 'badge-red';
    if (row.is_fallback) return 'badge-grey';
    return 'badge-green';
  }

  statusLabel(row: AiActiveModel): string {
    if (row.schema_status === 'SCHEMA_MISMATCH') return 'SCHEMA_MISMATCH';
    if (row.is_fallback) return 'Canonical fallback';
    return 'Trained';
  }
}
