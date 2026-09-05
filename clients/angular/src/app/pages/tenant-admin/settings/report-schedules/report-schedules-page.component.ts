import {
  AfterViewInit,
  Component,
  OnInit,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';

import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import {
  SelectComponent,
  SelectOption,
} from '../../../../shared/components/select/select.component';
import { ToastService } from '../../../../shared/components/toast/toast.service';
import { ConfirmService } from '../../../../shared/components/confirm-dialog/confirm.service';
import { TenantService } from '../../../../core/services/tenant.service';
import {
  TenantReportConfigRow,
  TenantReportConfigService,
} from '../../../../core/services/tenant-report-config.service';
import {
  CreateTenantReportScheduleRequest,
  ReportCadence,
  TenantReportScheduleRow,
  TenantReportScheduleService,
} from '../../../../core/services/tenant-report-schedule.service';
import {
  ScheduleRecipientListComponent,
} from './schedule-recipient-list.component';
import {
  ScheduleRunHistoryComponent,
} from './schedule-run-history.component';

interface ScheduleCard {
  row: TenantReportScheduleRow;
  familyLabel: string;
  configEnabled: boolean;   // false ⇒ cascade-disabled at the report-config layer
  saving: boolean;
  showRuns: boolean;
  showRecipients: boolean;
}

interface FamilyGroup {
  family: string;
  familyLabel: string;
  cards: ScheduleCard[];
}

interface EligibleCandidate {
  reportKey: string;
  label: string;
  family: string;
  familyLabel: string;
}

interface CreateDraft {
  reportKey: string;
  cadence: ReportCadence;
  hourOfDay: number;
  dayOfWeek: number | null;
  dayOfMonth: number | null;
  reportingCurrency: string | null;
  enabled: boolean;
}

const HOUR_OPTIONS: SelectOption[] = Array.from({ length: 24 }, (_, h) => ({
  value: h,
  label: `${String(h).padStart(2, '0')}:00`,
}));

const DAY_OF_WEEK_OPTIONS: SelectOption[] = [
  { value: 1, label: 'Monday' },
  { value: 2, label: 'Tuesday' },
  { value: 3, label: 'Wednesday' },
  { value: 4, label: 'Thursday' },
  { value: 5, label: 'Friday' },
  { value: 6, label: 'Saturday' },
  { value: 7, label: 'Sunday' },
];

// Cap at 28 to avoid the Feb boundary — the tenant admin picks a safe day
// that fires in every month rather than an out-of-range 29/30/31.
const DAY_OF_MONTH_OPTIONS: SelectOption[] = Array.from({ length: 28 }, (_, i) => ({
  value: i + 1,
  label: String(i + 1),
}));

const CADENCE_OPTIONS: SelectOption[] = [
  { value: 'WEEKLY',    label: 'Weekly' },
  { value: 'MONTHLY',   label: 'Monthly' },
  { value: 'QUARTERLY', label: 'Quarterly' },
  { value: 'ANNUAL',    label: 'Annual' },
];

/**
 * Phase 17 §C.1 admin surface — configure automated scheduled report
 * deliveries and the recipient list for each. Deep-linked from the Reports
 * tab (Phase 9) with a fragment anchor for scroll-to-schedule.
 *
 * <p>Data flow: fetches both {@link TenantReportScheduleService#list} (existing
 * schedules) and {@link TenantReportConfigService#list} (catalog — for family
 * grouping + the report-config-enabled state that gates cascade-disable UX).
 * Client-side merge keeps the response shapes clean without a joined DTO.
 */
@Component({
  selector: 'app-report-schedules-page',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    RouterLink,
    IconComponent,
    SkeletonComponent,
    SelectComponent,
    ScheduleRecipientListComponent,
    ScheduleRunHistoryComponent,
  ],
  templateUrl: './report-schedules-page.component.html',
  styleUrl: './report-schedules-page.component.scss',
})
export class ReportSchedulesPageComponent implements OnInit, AfterViewInit {
  groups: FamilyGroup[] = [];
  eligibleCandidates: EligibleCandidate[] = [];

  loading = false;
  errorMessage: string | null = null;

  readonly hourOptions = HOUR_OPTIONS;
  readonly dayOfWeekOptions = DAY_OF_WEEK_OPTIONS;
  readonly dayOfMonthOptions = DAY_OF_MONTH_OPTIONS;
  readonly cadenceOptions = CADENCE_OPTIONS;

  // Inline create form — one draft at a time, keyed by report key so opening
  // a second closes the first.
  createOpenFor: string | null = null;
  createDraft: CreateDraft = this.freshDraft();
  creating = false;

  private scrollToKey: string | null = null;

  constructor(
    private scheduleService: TenantReportScheduleService,
    private configService: TenantReportConfigService,
    private tenantService: TenantService,
    private route: ActivatedRoute,
    private toast: ToastService,
    private confirm: ConfirmService,
  ) {}

  ngOnInit(): void {
    this.route.fragment.subscribe(f => (this.scrollToKey = f));
    this.refresh();
  }

  ngAfterViewInit(): void {
    // Wait one macrotask so the accordion cards render before we scroll.
    setTimeout(() => this.scrollIfRequested(), 0);
  }

  refresh(): void {
    const tenantId = this.tenantService.getTenantId();
    if (!tenantId) {
      this.errorMessage = 'No active tenant context';
      return;
    }
    this.loading = true;
    this.errorMessage = null;
    this.configService.invalidate(tenantId);
    forkJoin({
      schedules: this.scheduleService.list(tenantId),
      catalog: this.configService.list(tenantId),
    }).subscribe({
      next: ({ schedules, catalog }) => {
        this.buildViewModel(schedules, catalog);
        this.loading = false;
        setTimeout(() => this.scrollIfRequested(), 0);
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Could not load report schedules.';
        this.loading = false;
      },
    });
  }

  private buildViewModel(schedules: TenantReportScheduleRow[],
                         catalog: TenantReportConfigRow[]): void {
    // Index catalog by reportKey once — enables family/label lookup + eligible
    // filtering without re-scanning.
    const byKey = new Map<string, TenantReportConfigRow>();
    for (const c of catalog) byKey.set(c.reportKey, c);

    const scheduledKeys = new Set(schedules.map(s => s.reportKey));

    // Existing schedules → cards grouped by family.
    const groupMap = new Map<string, FamilyGroup>();
    for (const s of schedules) {
      const config = byKey.get(s.reportKey);
      const familyKey = config?.family ?? 'OTHER';
      const familyLabel = config?.familyLabel ?? 'Other';
      let group = groupMap.get(familyKey);
      if (!group) {
        group = { family: familyKey, familyLabel, cards: [] };
        groupMap.set(familyKey, group);
      }
      group.cards.push({
        row: s,
        familyLabel,
        configEnabled: config?.enabled ?? true,
        saving: false,
        showRuns: false,
        showRecipients: true,
      });
    }
    this.groups = Array.from(groupMap.values());

    // Eligible-but-unscheduled → the "Add a schedule" section.
    // Catalog rows carry `cadenced=true` for the Phase 17 whitelist keys +
    // regulator keys the whitelist excludes. We rely on the fact that the
    // POST endpoint rejects non-whitelisted keys with 400 — the UI stays
    // permissive but validated server-side. If we later want to hide the
    // regulator keys from the CTA we'll wire the ScheduledReportEligibility
    // list into the API response.
    this.eligibleCandidates = catalog
      .filter(c => c.cadenced && !scheduledKeys.has(c.reportKey))
      .map(c => ({
        reportKey: c.reportKey,
        label: c.label,
        family: c.family ?? 'OTHER',
        familyLabel: c.familyLabel ?? 'Other',
      }))
      .sort((a, b) => a.label.localeCompare(b.label));
  }

  private scrollIfRequested(): void {
    if (!this.scrollToKey) return;
    const el = document.getElementById(this.scrollToKey);
    if (el) {
      el.scrollIntoView({ behavior: 'smooth', block: 'start' });
      el.classList.add('scroll-highlight');
      setTimeout(() => el.classList.remove('scroll-highlight'), 2000);
    }
    this.scrollToKey = null;
  }

  saveCard(card: ScheduleCard): void {
    const tenantId = this.tenantService.getTenantId();
    if (!tenantId) return;
    if (!this.validateShape(card.row.cadence,
                            card.row.dayOfWeek,
                            card.row.dayOfMonth)) {
      this.toast.error('Cadence needs its matching day: WEEKLY → dayOfWeek, MONTHLY → dayOfMonth.');
      return;
    }
    card.saving = true;
    this.scheduleService.update(tenantId, card.row.id, {
      enabled: card.row.enabled,
      cadence: card.row.cadence,
      hourOfDay: card.row.hourOfDay,
      dayOfWeek: card.row.dayOfWeek,
      dayOfMonth: card.row.dayOfMonth,
      reportingCurrency: card.row.reportingCurrency ?? null,
    }).subscribe({
      next: (updated) => {
        card.row = { ...updated, recipients: card.row.recipients };
        card.saving = false;
        this.toast.success('Schedule saved');
      },
      error: (err) => {
        card.saving = false;
        this.toast.error(err?.error?.detail || 'Could not save schedule');
      },
    });
  }

  toggleEnabled(card: ScheduleCard): void {
    // Bind runs immediately via ngModel; save right after so the toggle is
    // persistent without a "Save" click. Failure re-flips the toggle.
    const desired = card.row.enabled;
    this.saveCard(card);
    // If the report-config layer disabled this key, warn — the schedule may
    // now stay enabled but will never fire until the config is re-enabled.
    if (desired && !card.configEnabled) {
      this.toast.warning(
        'Report is disabled in Settings → Reports. This schedule will not fire until it is re-enabled.',
      );
    }
  }

  async deleteCard(card: ScheduleCard): Promise<void> {
    const tenantId = this.tenantService.getTenantId();
    if (!tenantId) return;
    const ok = await this.confirm.ask({
      title: `Delete this ${card.row.reportLabel} schedule?`,
      message: 'Recipient list will be removed. Delivery history is retained.',
      confirmLabel: 'Delete',
      danger: true,
    });
    if (!ok) return;
    this.scheduleService.delete(tenantId, card.row.id).subscribe({
      next: () => {
        this.toast.success('Schedule deleted');
        this.refresh();
      },
      error: (err) => this.toast.error(err?.error?.detail || 'Could not delete schedule'),
    });
  }

  openCreate(candidate: EligibleCandidate): void {
    this.createOpenFor = candidate.reportKey;
    this.createDraft = this.freshDraft();
    this.createDraft.reportKey = candidate.reportKey;
  }

  closeCreate(): void {
    this.createOpenFor = null;
    this.createDraft = this.freshDraft();
  }

  submitCreate(): void {
    const tenantId = this.tenantService.getTenantId();
    if (!tenantId || !this.createDraft.reportKey) return;
    if (!this.validateShape(this.createDraft.cadence,
                            this.createDraft.dayOfWeek,
                            this.createDraft.dayOfMonth)) {
      this.toast.error('Cadence needs its matching day: WEEKLY → dayOfWeek, MONTHLY → dayOfMonth.');
      return;
    }
    const body: CreateTenantReportScheduleRequest = {
      reportKey: this.createDraft.reportKey,
      enabled: this.createDraft.enabled,
      cadence: this.createDraft.cadence,
      hourOfDay: this.createDraft.hourOfDay,
      dayOfWeek: this.createDraft.cadence === 'WEEKLY' ? this.createDraft.dayOfWeek : null,
      dayOfMonth: this.createDraft.cadence === 'MONTHLY' ? this.createDraft.dayOfMonth : null,
      reportingCurrency: this.createDraft.reportingCurrency,
    };
    this.creating = true;
    this.scheduleService.create(tenantId, body).subscribe({
      next: () => {
        this.creating = false;
        this.toast.success('Schedule created');
        this.closeCreate();
        this.refresh();
      },
      error: (err) => {
        this.creating = false;
        this.toast.error(err?.error?.detail || 'Could not create schedule');
      },
    });
  }

  toggleRuns(card: ScheduleCard): void {
    card.showRuns = !card.showRuns;
  }

  toggleRecipients(card: ScheduleCard): void {
    card.showRecipients = !card.showRecipients;
  }

  onRecipientsChanged(card: ScheduleCard): void {
    this.refresh();
  }

  cadenceLabel(cadence: string): string {
    switch (cadence) {
      case 'WEEKLY':    return 'Weekly';
      case 'MONTHLY':   return 'Monthly';
      case 'QUARTERLY': return 'Quarterly';
      case 'ANNUAL':    return 'Annual';
      default:          return cadence;
    }
  }

  activeRecipientCount(card: ScheduleCard): number {
    return card.row.recipients.filter(r => r.isActive).length;
  }

  hourLabel(hour: number | null | undefined): string {
    const h = hour ?? 0;
    return `${String(h).padStart(2, '0')}:00`;
  }

  private validateShape(cadence: ReportCadence,
                        dayOfWeek: number | null,
                        dayOfMonth: number | null): boolean {
    if (cadence === 'WEEKLY' && (dayOfWeek == null)) return false;
    if (cadence === 'MONTHLY' && (dayOfMonth == null)) return false;
    return true;
  }

  private freshDraft(): CreateDraft {
    return {
      reportKey: '',
      cadence: 'MONTHLY',
      hourOfDay: 8,
      dayOfWeek: null,
      dayOfMonth: 1,
      reportingCurrency: null,
      enabled: true,
    };
  }
}
