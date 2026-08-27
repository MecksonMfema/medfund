import { Component, EventEmitter, Input, OnChanges, OnDestroy, Output, SimpleChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { IconComponent } from '../icon/icon.component';
import { JobStatusResponse } from '../../../core/services/actuarial-reports.service';

/**
 * Phase 14 §Actuarial Phase 10 — visual companion for the async job
 * polling loop. Renders a spinner + status label + elapsed clock + cancel
 * button while the job is in-flight; hides itself once the parent
 * observes a terminal state. Emits {@code cancel} when the user hits the
 * cancel button so the parent can unsubscribe its polling stream.
 *
 * <p>{@code jobId} is exposed as read-only copyable text — useful for
 * screenshotting / bug reporting when a job hangs on {@code failed}.
 */
@Component({
  selector: 'app-actuarial-job-progress',
  standalone: true,
  imports: [CommonModule, IconComponent],
  templateUrl: './actuarial-job-progress.component.html',
  styleUrl: './actuarial-job-progress.component.scss',
})
export class ActuarialJobProgressComponent implements OnChanges, OnDestroy {
  @Input() status: JobStatusResponse | null = null;
  @Input() startedAt: number | null = null;
  @Output() cancel = new EventEmitter<void>();

  elapsedSec = 0;
  private tick: ReturnType<typeof setInterval> | null = null;
  copied = false;

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['startedAt']) this.restartTicker();
    if (changes['status']) this.maybeStopTickerOnTerminal();
  }

  ngOnDestroy(): void { this.stopTicker(); }

  onCancel(): void {
    this.cancel.emit();
    this.stopTicker();
  }

  copyJobId(): void {
    const id = this.status?.jobId;
    if (!id) return;
    void navigator.clipboard.writeText(id).then(() => {
      this.copied = true;
      setTimeout(() => (this.copied = false), 1500);
    }).catch(() => { /* fall through — copy is a courtesy */ });
  }

  get statusLabel(): string {
    switch (this.status?.status) {
      case 'requested':  return 'Queued';
      case 'processing': return 'Computing';
      case 'completed':  return 'Ready';
      case 'failed':     return 'Failed';
      default:           return 'Preparing';
    }
  }

  get isTerminal(): boolean {
    return this.status?.status === 'completed' || this.status?.status === 'failed';
  }

  get progressPct(): number {
    return this.status?.progressPct ?? 0;
  }

  private restartTicker(): void {
    this.stopTicker();
    if (this.startedAt == null) return;
    const bump = () => {
      this.elapsedSec = Math.max(0, Math.floor((Date.now() - (this.startedAt ?? Date.now())) / 1000));
    };
    bump();
    this.tick = setInterval(bump, 1000);
  }

  private maybeStopTickerOnTerminal(): void {
    if (this.isTerminal) this.stopTicker();
  }

  private stopTicker(): void {
    if (this.tick != null) {
      clearInterval(this.tick);
      this.tick = null;
    }
  }
}
