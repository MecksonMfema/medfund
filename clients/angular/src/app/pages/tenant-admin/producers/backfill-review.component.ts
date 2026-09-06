import { CommonModule } from '@angular/common';
import { Component, OnDestroy, OnInit } from '@angular/core';
import { Subscription, interval } from 'rxjs';
import { startWith, switchMap } from 'rxjs/operators';
import {
  BackfillCandidate,
  BackfillProgress,
  ProducerService,
} from '../../../core/services/producer.service';
import { IconComponent } from '../../../shared/components/icon/icon.component';

/**
 * Tenant-admin surface for the treaty.producer_ref → producer_id fuzzy
 * backfill (Phase 10 §B). Header shows a progress card + "Run now" button;
 * body is a paginated table of PENDING candidates. Confidence bars are
 * colour-coded per the plan's Manual Verification bullet
 * (green ≥ 0.900, amber 0.700–0.899, red < 0.700 — though ≥ 0.900 rows
 * auto-accept and never land in the pending table).
 */
@Component({
  selector: 'app-backfill-review',
  standalone: true,
  imports: [CommonModule, IconComponent],
  templateUrl: './backfill-review.component.html',
  styleUrl: './backfill-review.component.scss',
})
export class BackfillReviewComponent implements OnInit, OnDestroy {
  progress: BackfillProgress = {
    startedAt: null, completedAt: null,
    processed: 0, skipped: 0, autoAccepted: 0, pending: 0, failed: 0,
    running: false, errorMessage: null,
  };
  candidates: BackfillCandidate[] = [];

  loading = false;
  saving = false;
  running = false;
  errorMessage: string | null = null;
  successMessage: string | null = null;

  page = 1;
  pageSize = 50;

  private pollSub: Subscription | null = null;

  constructor(private svc: ProducerService) {}

  ngOnInit(): void {
    this.fetchCandidates();
    this.startPolling();
  }

  ngOnDestroy(): void { this.pollSub?.unsubscribe(); }

  startPolling(): void {
    // 2s cadence matches the plan's Automated Verification bullet
    // ("progress updates poll every 2s").
    this.pollSub = interval(2000)
      .pipe(
        startWith(0),
        switchMap(() => this.svc.getBackfillProgress()))
      .subscribe({
        next: (p) => {
          this.progress = p;
          this.running = p.running;
        },
        error: () => {
          // Silence — the backend hasn't run the job yet, that's fine.
        },
      });
  }

  runBackfill(): void {
    if (this.running || this.saving) return;
    this.saving = true;
    this.errorMessage = null;
    this.successMessage = null;
    this.svc.runProducerBackfill().subscribe({
      next: () => {
        this.saving = false;
        this.running = true;
        this.successMessage = 'Backfill started: progress will refresh below.';
        setTimeout(() => this.fetchCandidates(), 3000);
      },
      error: (err) => {
        this.saving = false;
        this.errorMessage = err?.error?.message ?? 'Failed to start backfill.';
      },
    });
  }

  fetchCandidates(): void {
    this.loading = true;
    this.svc.listBackfillCandidates(this.page - 1, this.pageSize).subscribe({
      next: (rows) => {
        this.candidates = rows ?? [];
        this.loading = false;
      },
      error: (err) => {
        this.loading = false;
        this.errorMessage = err?.error?.message ?? 'Failed to load pending candidates.';
      },
    });
  }

  onPageChange(page: number): void {
    this.page = page;
    this.fetchCandidates();
  }

  accept(c: BackfillCandidate): void {
    this.actOn(c.id, () => this.svc.acceptBackfillCandidate(c.id),
        `Accepted candidate ${c.candidateProducerName}`);
  }

  reject(c: BackfillCandidate): void {
    this.actOn(c.id, () => this.svc.rejectBackfillCandidate(c.id),
        `Rejected candidate ${c.candidateProducerName}`);
  }

  private actOn(id: string, action: () => import('rxjs').Observable<void>, ok: string): void {
    this.errorMessage = null;
    this.successMessage = null;
    action().subscribe({
      next: () => {
        this.successMessage = ok;
        this.candidates = this.candidates.filter(c => c.id !== id);
      },
      error: (err) => {
        this.errorMessage = err?.error?.message ?? 'Action failed.';
      },
    });
  }

  /** Colour band for the confidence bar. */
  confidenceBand(score: number): 'green' | 'amber' | 'red' {
    if (score >= 0.900) return 'green';
    if (score >= 0.700) return 'amber';
    return 'red';
  }

  confidencePercent(score: number): number {
    return Math.round(score * 1000) / 10;
  }
}
