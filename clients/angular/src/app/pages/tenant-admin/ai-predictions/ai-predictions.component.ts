import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import {
  DataTableComponent,
  TableAction,
} from '../../../shared/components/data-table/data-table.component';
import { IconComponent } from '../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../shared/components/select/select.component';
import {
  AiPredictionDetail,
  AiPredictionRow,
  AiPredictionsService,
  AiReviewQueueBatch,
} from '../../../core/services/ai-predictions.service';

/**
 * Tenant-admin console for the AI predictions audit trail. Backs Critical
 * Rule #3 — humans can inspect every AI-influenced decision and record an
 * accept / override verdict that lands as a persisted review row.
 */
@Component({
  selector: 'app-ai-predictions',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, DataTableComponent, IconComponent, SelectComponent],
  templateUrl: './ai-predictions.component.html',
  styleUrl: './ai-predictions.component.scss',
})
export class AiPredictionsComponent implements OnInit {
  predictions: AiPredictionRow[] = [];
  totalCount = 0;
  totalPages = 1;
  currentPage = 1;
  loading = false;
  readonly pageSize = 50;

  // Filter model
  insuranceLine = '';
  entityType = '';
  predictionType = '';
  accepted: '' | 'true' | 'false' = '';

  // Detail modal
  selected: AiPredictionDetail | null = null;
  detailLoading = false;
  feedback = '';

  // Queue mode (Tranche 1 per G1) — 50/50 HIGH/LOW stratified batch that
  // reviewers work through one row at a time so overrides accumulate on
  // both buckets.
  mode: 'list' | 'queue' = 'list';
  queueModelType: 'fraud' = 'fraud';
  queueSize = 20;
  queueBatch: AiPredictionDetail[] = [];
  queueStats: { total_unreviewed: number; high_count: number; low_count: number; other_count: number } | null = null;
  queueIndex = 0;
  queueLoading = false;
  queueFeedback = '';
  queueSubmitting = false;
  queueError = '';

  // ── Static filter options ────────────────────────────────────────────────

  readonly insuranceLineOptions: SelectOption[] = [
    { value: 'HEALTH',     label: 'Health' },
    { value: 'LIFE',       label: 'Life' },
    { value: 'FUNERAL',    label: 'Funeral' },
    { value: 'GROUP',      label: 'Group' },
    { value: 'TRAVEL',     label: 'Travel' },
    { value: 'DISABILITY', label: 'Disability' },
    { value: 'VEHICLE',    label: 'Motor' },
    { value: 'PROPERTY',   label: 'Property' },
  ];

  readonly entityTypeOptions: SelectOption[] = [
    { value: 'claim',        label: 'Claim' },
    { value: 'conversation', label: 'Conversation' },
    { value: 'document',     label: 'Document' },
    { value: 'member',       label: 'Member' },
    { value: 'asset',        label: 'Asset' },
  ];

  readonly predictionTypeOptions: SelectOption[] = [
    { value: 'adjudication',    label: 'Adjudication' },
    { value: 'fraud',           label: 'Fraud' },
    { value: 'duplicate',       label: 'Duplicate' },
    { value: 'code_suggestion', label: 'Code Suggestion' },
    { value: 'ocr',             label: 'OCR' },
    { value: 'chat',            label: 'Chat' },
    { value: 'pricing',         label: 'Pricing' },
  ];

  readonly acceptedOptions: SelectOption[] = [
    { value: 'true',  label: 'Accepted' },
    { value: 'false', label: 'Overridden' },
  ];

  // ── Table configuration ──────────────────────────────────────────────────

  columns = [
    { key: 'created_at',      label: 'When',      type: 'date' },
    { key: 'insurance_line',  label: 'Line' },
    { key: 'prediction_type', label: 'Type',      type: 'status' },
    { key: 'entity_type',     label: 'Entity' },
    { key: 'entity_id',       label: 'Entity ID' },
    { key: 'model_version',   label: 'Model' },
    { key: 'confidence',      label: 'Confidence' },
    { key: '_decision',       label: 'Decision',  type: 'status' },
  ];

  tableActions: TableAction[] = [
    {
      label: 'View',
      icon: 'eye',
      color: 'default',
      handler: (row) => this.openDetail(row.id),
    },
  ];

  constructor(private svc: AiPredictionsService) {}

  ngOnInit(): void {
    this.loadPage(1);
  }

  loadPage(page: number): void {
    this.loading = true;
    this.svc
      .list({
        insurance_line:  this.insuranceLine  || undefined,
        entity_type:     this.entityType     || undefined,
        prediction_type: this.predictionType || undefined,
        accepted:        this.accepted       || undefined,
        page: page - 1,
        size: this.pageSize,
      })
      .subscribe({
        next: (data) => {
          this.totalCount = data.total;
          this.totalPages = Math.max(Math.ceil(this.totalCount / this.pageSize), 1);
          this.currentPage = page;
          this.predictions = data.items.map((r) => this.decorate(r));
          this.loading = false;
        },
        error: () => {
          this.predictions = [];
          this.loading = false;
        },
      });
  }

  applyFilters(): void {
    this.loadPage(1);
  }

  openDetail(id: string): void {
    this.detailLoading = true;
    this.feedback = '';
    this.svc.get(id).subscribe({
      next: (row) => {
        this.selected = row;
        this.detailLoading = false;
      },
      error: () => {
        this.detailLoading = false;
        this.selected = null;
      },
    });
  }

  closeDetail(): void {
    this.selected = null;
    this.feedback = '';
  }

  decide(accepted: boolean): void {
    if (!this.selected) return;
    const id = this.selected.id;
    const trimmed = (this.feedback || '').trim();
    this.svc.decide(id, accepted, trimmed || null).subscribe({
      next: () => {
        this.closeDetail();
        this.loadPage(this.currentPage);
      },
      // Fail silently — the row will still show its old state on refresh.
    });
  }

  private decorate(row: AiPredictionRow): AiPredictionRow & { _decision: string } {
    let decision: string;
    if (row.accepted === true) decision = 'Accepted';
    else if (row.accepted === false) decision = 'Overridden';
    else decision = 'Pending';
    return { ...row, _decision: decision };
  }

  formatFeatures(v: unknown): string {
    if (v == null) return '-';
    try {
      return JSON.stringify(v, null, 2);
    } catch {
      return String(v);
    }
  }

  // ── Queue mode (Phase 0, per G1) ──────────────────────────────────────────

  setMode(mode: 'list' | 'queue'): void {
    this.mode = mode;
    if (mode === 'queue' && this.queueBatch.length === 0) {
      this.loadQueue();
    }
  }

  loadQueue(): void {
    this.queueLoading = true;
    this.queueError = '';
    this.queueIndex = 0;
    this.queueFeedback = '';
    this.svc
      .reviewQueue(this.queueModelType, this.queueSize, this.insuranceLine || undefined)
      .subscribe({
        next: (batch: AiReviewQueueBatch) => {
          this.queueBatch = batch.items;
          this.queueStats = {
            total_unreviewed: batch.total_unreviewed,
            high_count: batch.high_count,
            low_count: batch.low_count,
            other_count: batch.other_count,
          };
          this.queueLoading = false;
        },
        error: () => {
          this.queueBatch = [];
          this.queueStats = null;
          this.queueError = 'Unable to load review queue.';
          this.queueLoading = false;
        },
      });
  }

  get currentQueueItem(): AiPredictionDetail | null {
    return this.queueBatch[this.queueIndex] ?? null;
  }

  get queueRiskLevel(): string {
    const item = this.currentQueueItem;
    const level = (item?.output as { risk_level?: string } | undefined)?.risk_level;
    return level ?? '';
  }

  decideQueue(accepted: boolean): void {
    const item = this.currentQueueItem;
    if (!item || this.queueSubmitting) return;
    this.queueSubmitting = true;
    const trimmed = (this.queueFeedback || '').trim();
    this.svc.decide(item.id, accepted, trimmed || null).subscribe({
      next: () => {
        this.queueSubmitting = false;
        this.queueFeedback = '';
        if (this.queueIndex + 1 < this.queueBatch.length) {
          this.queueIndex += 1;
        } else {
          // Batch exhausted — refresh from the server.
          this.loadQueue();
        }
      },
      error: () => {
        this.queueSubmitting = false;
        this.queueError = 'Save failed — try again.';
      },
    });
  }

  skipQueueItem(): void {
    if (this.queueIndex + 1 < this.queueBatch.length) {
      this.queueIndex += 1;
      this.queueFeedback = '';
    } else {
      this.loadQueue();
    }
  }
}
