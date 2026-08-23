import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import {
  Assignment,
  BulkReassignPayload,
  BulkReassignReport,
  Producer,
  ProducerService,
} from '../../../core/services/producer.service';
import { IconComponent } from '../../../shared/components/icon/icon.component';
import { firstOfMonth, firstOfMonthOffset } from '../../../shared/utils/date-snap';

/**
 * Post-termination bulk-reassign surface. Route:
 * {@code /tenant/admin/producers/:id/reassign}. The path {@code :id} is the
 * source (terminated) producer; the operator selects members from that
 * producer's still-open assignments — some assignments may already have
 * been closed by the terminate flow (the /assignments endpoint returns the
 * remaining open ones) — picks a successor via the debounced search-select,
 * and submits. Server-side batch: one HTTP call per submission, one
 * transaction per member (per-row failures reported without failing the
 * batch).
 */
@Component({
  selector: 'app-bulk-reassign',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent],
  templateUrl: './bulk-reassign.component.html',
  styleUrl: './producers-list.component.scss',
})
export class BulkReassignComponent implements OnInit {
  sourceProducer: Producer | null = null;
  rows: Assignment[] = [];
  openCount: number | null = null;
  loading = false;
  submitting = false;
  errorMessage: string | null = null;

  page = 1;
  pageSize = 50;
  selected = new Set<string>();

  // Successor picker
  newProducer: Producer | null = null;
  successorSearchQuery = '';
  successorMatches: Producer[] = [];
  successorSearching = false;
  private successorSearchTimer: ReturnType<typeof setTimeout> | null = null;

  effectiveFrom = firstOfMonthOffset(0);
  changeReason = '';

  report: BulkReassignReport | null = null;

  constructor(private route: ActivatedRoute,
              private router: Router,
              private svc: ProducerService) {}

  ngOnInit(): void {
    const sourceId = this.route.snapshot.paramMap.get('id');
    if (!sourceId) {
      this.errorMessage = 'Missing producer id';
      return;
    }
    this.loadSource(sourceId);
    this.fetch(sourceId);
  }

  private loadSource(id: string): void {
    this.svc.getProducer(id).subscribe({
      next: p => { this.sourceProducer = p; },
      error: err => {
        this.errorMessage = err?.error?.detail || 'Failed to load producer';
      },
    });
    this.svc.countOpenAssignments(id).subscribe({
      next: c => { this.openCount = c; },
    });
  }

  private fetch(sourceId: string): void {
    this.loading = true;
    this.svc.listProducerAssignments(sourceId, this.page - 1, this.pageSize).subscribe({
      next: rows => {
        this.rows = rows;
        this.loading = false;
      },
      error: err => {
        this.errorMessage = err?.error?.detail || 'Failed to load assignments';
        this.rows = [];
        this.loading = false;
      },
    });
  }

  onPageChange(delta: number): void {
    if (delta < 0 && this.page <= 1) return;
    this.page += delta;
    if (this.sourceProducer) this.fetch(this.sourceProducer.id);
  }

  toggle(memberId: string): void {
    if (this.selected.has(memberId)) this.selected.delete(memberId);
    else this.selected.add(memberId);
  }

  isChecked(memberId: string): boolean {
    return this.selected.has(memberId);
  }

  allOnPageChecked(): boolean {
    return this.rows.length > 0 && this.rows.every(r => this.selected.has(r.memberId));
  }

  toggleAllOnPage(): void {
    if (this.allOnPageChecked()) {
      this.rows.forEach(r => this.selected.delete(r.memberId));
    } else {
      this.rows.forEach(r => this.selected.add(r.memberId));
    }
  }

  clearSelection(): void { this.selected.clear(); }

  // ── successor picker ─────────────────────────────────────────────────────
  onSuccessorSearchChange(): void {
    if (this.successorSearchTimer) clearTimeout(this.successorSearchTimer);
    const q = this.successorSearchQuery.trim();
    if (!q) { this.successorMatches = []; return; }
    this.successorSearching = true;
    this.successorSearchTimer = setTimeout(() => {
      this.svc.searchProducers(q, 10).subscribe({
        next: rows => {
          this.successorMatches = rows.filter(r =>
            r.id !== this.sourceProducer?.id && r.active);
          this.successorSearching = false;
        },
        error: () => { this.successorMatches = []; this.successorSearching = false; },
      });
    }, 300);
  }

  pickSuccessor(p: Producer): void {
    this.newProducer = p;
    this.successorSearchQuery = '';
    this.successorMatches = [];
  }

  clearSuccessor(): void { this.newProducer = null; }

  onEffectiveFromChange(): void {
    this.effectiveFrom = firstOfMonth(this.effectiveFrom);
  }

  submit(): void {
    if (!this.sourceProducer) return;
    if (!this.newProducer) {
      this.errorMessage = 'Pick a successor producer first';
      return;
    }
    if (this.selected.size === 0) {
      this.errorMessage = 'Select at least one member to reassign';
      return;
    }
    const snapped = firstOfMonth(this.effectiveFrom);
    if (!/^\d{4}-\d{2}-\d{2}$/.test(snapped)) {
      this.errorMessage = 'Effective from must be YYYY-MM-DD';
      return;
    }
    this.errorMessage = null;
    this.submitting = true;
    const payload: BulkReassignPayload = {
      newProducerId: this.newProducer.id,
      memberIds: Array.from(this.selected),
      effectiveFrom: snapped,
      changeReason: this.changeReason.trim() || null,
    };
    this.svc.bulkReassign(this.sourceProducer.id, payload).subscribe({
      next: report => {
        this.report = report;
        this.submitting = false;
        this.selected.clear();
        if (this.sourceProducer) {
          this.fetch(this.sourceProducer.id);
          this.svc.countOpenAssignments(this.sourceProducer.id).subscribe({
            next: c => { this.openCount = c; },
          });
        }
      },
      error: err => {
        this.submitting = false;
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Bulk reassign failed';
      },
    });
  }

  closeReport(): void { this.report = null; }

  backToProducer(): void {
    if (this.sourceProducer) {
      this.router.navigate(['/tenant/admin/producers/list']);
    }
  }
}
