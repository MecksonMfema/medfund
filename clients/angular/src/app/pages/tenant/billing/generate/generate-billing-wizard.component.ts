import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Subscription, interval, switchMap, takeWhile, tap } from 'rxjs';
import {
  BillingCommitResponse,
  BillingFilterPayload,
  BillingPreviewResponse,
  ContributionsService,
  EnqueueBillingPayload,
  ScheduledJobRun,
} from '../../../../core/services/contributions.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { CurrencyFormatPipe } from '../../../../shared/pipes/currency-format.pipe';

type WizardStep = 'filters' | 'preview' | 'committed';

/** Short-poll cadence — see CLAUDE memory: "Stats must be server-side". The
 *  backend can serve any number of pollers cheaply; the runs table is indexed
 *  on (config_id, started_at DESC). 2.5s strikes a balance between perceived
 *  latency and request volume for a wizard that's only open during the run. */
const POLL_INTERVAL_MS = 2500;

@Component({
  selector: 'app-generate-billing-wizard',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent, CurrencyFormatPipe],
  templateUrl: './generate-billing-wizard.component.html',
  styleUrl: './generate-billing-wizard.component.scss',
})
export class GenerateBillingWizardComponent implements OnInit, OnDestroy {
  step: WizardStep = 'filters';
  errorMessage: string | null = null;
  /** True while the preview job is running in the background. */
  loading = false;
  /** True while the commit job is running in the background. */
  saving = false;
  /** Elapsed seconds since the current background job started — shown
   *  alongside the spinner so the user knows it's still alive. */
  elapsedSeconds = 0;

  // Period defaults to current month.
  periodStart = '';
  periodEnd = '';

  preview: BillingPreviewResponse | null = null;
  committedAt: string | null = null;
  committedCount = 0;
  committedTotals: Record<string, string> = {};
  committedGroupInvoices = 0;
  committedIndividualInvoices = 0;
  committedMembershipModel = '';

  private pollSubscription: Subscription | null = null;
  private jobStartMs = 0;

  constructor(private contributions: ContributionsService) {}

  ngOnInit(): void {
    const today = new Date();
    const start = new Date(today.getFullYear(), today.getMonth(), 1);
    const end = new Date(today.getFullYear(), today.getMonth() + 1, 0);
    this.periodStart = start.toISOString().slice(0, 10);
    this.periodEnd = end.toISOString().slice(0, 10);
  }

  ngOnDestroy(): void {
    this.stopPolling();
  }

  /** Friendly label for the tenant's membership model banner. */
  membershipBanner(model: string | undefined): string {
    switch (model) {
      case 'GROUP_ONLY':      return 'Groups only — every member must belong to a group; invoices go to liaisons.';
      case 'INDIVIDUAL_ONLY': return 'Individuals only — one invoice per member.';
      case 'BOTH':            return 'Mixed — grouped members billed via their group liaison; ungrouped members billed individually.';
      default:                return '';
    }
  }

  totalsKeys(map: Record<string, string> | undefined): string[] {
    return map ? Object.keys(map) : [];
  }

  runPreview(): void {
    if (!this.periodStart || !this.periodEnd) {
      this.errorMessage = 'Pick a period';
      return;
    }
    this.loading = true;
    this.errorMessage = null;
    const payload: EnqueueBillingPayload = {
      kind: 'preview',
      periodStart: this.periodStart,
      periodEnd: this.periodEnd,
    };
    this.contributions.enqueueBilling(payload).subscribe({
      next: (resp) => {
        // The dispatcher today runs jobs synchronously inside runNow, so the
        // returned status is usually already SUCCESS — we still poll so the
        // UI works the same way once the dispatcher moves jobs onto a
        // worker thread pool. The first poll either picks up the result
        // immediately or finds RUNNING and continues.
        this.pollJob(resp.configId, 'preview');
      },
      error: (err) => {
        this.loading = false;
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Could not enqueue preview';
      },
    });
  }

  commit(): void {
    if (!this.preview) return;
    this.saving = true;
    this.errorMessage = null;
    const payload: EnqueueBillingPayload = {
      kind: 'commit',
      periodStart: this.periodStart,
      periodEnd: this.periodEnd,
    };
    this.contributions.enqueueBilling(payload).subscribe({
      next: (resp) => this.pollJob(resp.configId, 'commit'),
      error: (err) => {
        this.saving = false;
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Could not enqueue commit';
      },
    });
  }

  backToFilters(): void {
    this.step = 'filters';
    this.preview = null;
  }

  startAnother(): void {
    this.step = 'filters';
    this.preview = null;
    this.committedAt = null;
    this.committedCount = 0;
    this.committedTotals = {};
    this.committedGroupInvoices = 0;
    this.committedIndividualInvoices = 0;
    this.committedMembershipModel = '';
  }

  // ── Polling ──────────────────────────────────────────────────────────────

  private pollJob(configId: string, kind: 'preview' | 'commit'): void {
    this.stopPolling();
    this.jobStartMs = Date.now();
    this.elapsedSeconds = 0;
    this.pollSubscription = interval(POLL_INTERVAL_MS).pipe(
      tap(() => { this.elapsedSeconds = Math.floor((Date.now() - this.jobStartMs) / 1000); }),
      switchMap(() => this.contributions.listJobRuns(configId, 1)),
      takeWhile((rows) => rows.length === 0 || rows[0].status === 'RUNNING', true),
    ).subscribe({
      next: (rows) => {
        if (rows.length === 0) return;
        const run = rows[0];
        if (run.status === 'RUNNING') return;
        this.stopPolling();
        if (run.status === 'FAILED') {
          this.handleFailure(run, kind);
        } else {
          this.handleSuccess(run, kind);
        }
      },
      error: () => {
        this.stopPolling();
        this.loading = false;
        this.saving = false;
        this.errorMessage = 'Lost track of the billing job — refresh and check the job runs page.';
      },
    });
  }

  private stopPolling(): void {
    this.pollSubscription?.unsubscribe();
    this.pollSubscription = null;
  }

  private handleSuccess(run: ScheduledJobRun, kind: 'preview' | 'commit'): void {
    if (!run.resultPayload) {
      this.loading = false;
      this.saving = false;
      this.errorMessage = 'Job finished but returned no result — check the runs log.';
      return;
    }
    try {
      const parsed = JSON.parse(run.resultPayload);
      if (kind === 'preview') {
        this.preview = parsed as BillingPreviewResponse;
        this.loading = false;
        this.step = 'preview';
      } else {
        const commit = parsed as BillingCommitResponse;
        this.saving = false;
        this.committedCount = commit.contributionsCreated;
        this.committedTotals = commit.totalsByCurrency;
        this.committedAt = commit.committedAt;
        this.committedGroupInvoices = commit.groupInvoicesCreated;
        this.committedIndividualInvoices = commit.individualInvoicesCreated;
        this.committedMembershipModel = commit.membershipModel;
        this.step = 'committed';
      }
    } catch {
      this.loading = false;
      this.saving = false;
      this.errorMessage = 'Could not parse the job result.';
    }
  }

  private handleFailure(run: ScheduledJobRun, kind: 'preview' | 'commit'): void {
    this.loading = false;
    this.saving = false;
    const label = kind === 'preview' ? 'Preview' : 'Commit';
    this.errorMessage = run.errorMessage ? `${label} failed: ${run.errorMessage}` : `${label} failed`;
  }
}
