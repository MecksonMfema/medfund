import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  AdminService,
  ScheduledJob,
  ScheduledJobRun,
} from '../../../core/services/admin.service';
import { IconComponent } from '../../../shared/components/icon/icon.component';
import { SkeletonComponent } from '../../../shared/components/skeleton/skeleton.component';
import { HumanizePipe } from '../../../shared/pipes/humanize.pipe';

@Component({
  selector: 'app-jobs-monitor',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, SkeletonComponent, HumanizePipe],
  templateUrl: './jobs-monitor.component.html',
  styleUrl: './jobs-monitor.component.scss',
})
export class JobsMonitorComponent implements OnInit {
  jobs: ScheduledJob[] = [];
  selected: ScheduledJob | null = null;
  runs: ScheduledJobRun[] = [];

  loadingJobs = false;
  loadingRuns = false;
  busyJobId: string | null = null;
  errorMessage: string | null = null;
  successMessage: string | null = null;

  constructor(private admin: AdminService) {}

  ngOnInit(): void {
    this.refreshJobs();
  }

  refreshJobs(): void {
    this.loadingJobs = true;
    this.admin.getScheduledJobs().subscribe({
      next: (rows) => {
        this.jobs = rows;
        this.loadingJobs = false;
        // Re-select if we had one open; otherwise pick the first.
        const previousId = this.selected?.id;
        if (previousId) {
          this.selected = rows.find(j => j.id === previousId) || null;
        } else if (rows.length) {
          this.select(rows[0]);
        }
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load scheduled jobs';
        this.loadingJobs = false;
      },
    });
  }

  select(job: ScheduledJob): void {
    this.selected = job;
    this.refreshRuns(job.id);
  }

  refreshRuns(id: string): void {
    this.loadingRuns = true;
    this.admin.listJobRuns(id, 50).subscribe({
      next: (rows) => { this.runs = rows; this.loadingRuns = false; },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load runs';
        this.loadingRuns = false;
      },
    });
  }

  toggleEnabled(job: ScheduledJob, event: Event): void {
    event.stopPropagation();
    this.busyJobId = job.id;
    const stream = job.isEnabled
      ? this.admin.disableJob(job.id)
      : this.admin.enableJob(job.id);
    stream.subscribe({
      next: () => {
        job.isEnabled = !job.isEnabled;
        this.busyJobId = null;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to toggle job';
        this.busyJobId = null;
      },
    });
  }

  runNow(job: ScheduledJob): void {
    if (!confirm(`Run ${job.name} now? This bypasses the cron schedule and records a manual run.`)) return;
    this.busyJobId = job.id;
    this.admin.runJobNow(job.id).subscribe({
      next: (run) => {
        this.successMessage = `Manual run ${run.status === 'SUCCESS' ? 'succeeded' : 'started'} (${run.id.substring(0, 8)}).`;
        this.busyJobId = null;
        this.refreshJobs();
        if (this.selected?.id === job.id) this.refreshRuns(job.id);
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to run job';
        this.busyJobId = null;
      },
    });
  }

  failureCount(): number {
    return this.runs.filter(r => r.status === 'FAILED').length;
  }
}
