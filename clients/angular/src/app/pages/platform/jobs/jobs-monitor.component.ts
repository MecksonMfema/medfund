import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  AdminService,
  ScheduledJob,
  ScheduledJobRun,
  Tenant,
} from '../../../core/services/admin.service';
import { IconComponent } from '../../../shared/components/icon/icon.component';
import { SkeletonComponent } from '../../../shared/components/skeleton/skeleton.component';
import { HumanizePipe } from '../../../shared/pipes/humanize.pipe';
import { TenantPickerComponent } from '../../../shared/components/tenant-picker/tenant-picker.component';
import { SelectComponent, SelectOption } from '../../../shared/components/select/select.component';

@Component({
  selector: 'app-jobs-monitor',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, SkeletonComponent, HumanizePipe, TenantPickerComponent, SelectComponent],
  templateUrl: './jobs-monitor.component.html',
  styleUrl: './jobs-monitor.component.scss',
})
export class JobsMonitorComponent implements OnInit {
  jobs: ScheduledJob[] = [];
  selected: ScheduledJob | null = null;
  runs: ScheduledJobRun[] = [];

  /** null = "All tenants" (cross-tenant view, the default for super admin). */
  tenantFilter: string | null = null;

  /** Cached id → name map so the Tenant column can render labels without
      re-fetching the tenant list per row. */
  private tenantsById: Map<string, string> = new Map();

  loadingJobs = false;
  loadingRuns = false;
  busyJobId: string | null = null;
  busy = false;
  errorMessage: string | null = null;
  successMessage: string | null = null;

  // ── Add-job form state ──
  showAddForm = false;
  jobTypes: { type: string; displayName: string; description: string }[] = [];
  newJob = this.blankJob();

  /** Job-type catalogue as SelectOption[] — used by the inline add-job form. */
  get jobTypeOptions(): SelectOption[] {
    return this.jobTypes.map(jt => ({ value: jt.type, label: jt.displayName, description: jt.description }));
  }

  constructor(private admin: AdminService) {}

  private blankJob() {
    return {
      tenantId: null as string | null,
      jobType: '',
      name: '',
      cronExpression: '0 0 0 * * *',
      settings: '{}',
    };
  }

  ngOnInit(): void {
    // Tenant labels for the Tenant column. Same call the picker makes —
    // both cache server-side at the API layer, so this is cheap.
    this.admin.getTenants({ size: 500 }).subscribe({
      next: (page) => {
        this.tenantsById = new Map(page.content.map((t: Tenant) => [t.id, t.name]));
      },
      error: () => { /* labels fall back to id substring on miss */ },
    });
    this.admin.listJobTypes().subscribe({
      next: (rows) => { this.jobTypes = rows; },
      error: () => { this.jobTypes = []; },
    });
    this.refreshJobs();
  }

  // ── Add-job form ──

  openAddForm(): void {
    this.newJob = this.blankJob();
    if (this.tenantFilter) this.newJob.tenantId = this.tenantFilter;
    if (this.jobTypes.length) this.newJob.jobType = this.jobTypes[0].type;
    this.showAddForm = true;
  }

  cancelAdd(): void { this.showAddForm = false; }

  submitAdd(): void {
    if (!this.newJob.jobType || !this.newJob.name.trim() || !this.newJob.cronExpression.trim()) {
      this.errorMessage = 'Job type, name, and cron expression are required.';
      return;
    }
    this.busy = true;
    this.admin.createScheduledJob({
      jobType: this.newJob.jobType,
      name: this.newJob.name.trim(),
      cronExpression: this.newJob.cronExpression.trim(),
      settings: this.newJob.settings.trim() || '{}',
      tenantId: this.newJob.tenantId,
    }).subscribe({
      next: () => {
        this.successMessage = `Job "${this.newJob.name}" added.`;
        this.busy = false;
        this.showAddForm = false;
        this.refreshJobs();
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to create job';
        this.busy = false;
      },
    });
  }

  // ── Seed defaults ──

  seedDefaults(): void {
    const target = this.tenantFilter
      ? `tenant ${this.tenantLabel(this.tenantFilter)}`
      : 'platform-wide (no tenant)';
    if (!confirm(`Seed the 6 default jobs for ${target}? Existing jobs are not modified.`)) return;
    this.busy = true;
    this.admin.seedDefaultJobs(this.tenantFilter).subscribe({
      next: () => {
        this.successMessage = `Defaults seeded for ${target}.`;
        this.busy = false;
        this.refreshJobs();
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to seed defaults';
        this.busy = false;
      },
    });
  }

  tenantLabel(tenantId: string | null): string {
    if (!tenantId) return 'Platform';
    return this.tenantsById.get(tenantId) || tenantId.substring(0, 8);
  }

  onTenantFilterChange(): void {
    this.selected = null;
    this.runs = [];
    this.refreshJobs();
  }

  refreshJobs(): void {
    this.loadingJobs = true;
    this.admin.getScheduledJobs(this.tenantFilter).subscribe({
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
    this.admin.listJobRuns(id, 50, this.tenantFilter).subscribe({
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
