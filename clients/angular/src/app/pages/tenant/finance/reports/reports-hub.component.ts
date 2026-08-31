import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { forkJoin, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { TenantService } from '../../../../core/services/tenant.service';
import {
  TenantReportConfigRow,
  TenantReportConfigService,
} from '../../../../core/services/tenant-report-config.service';
import {
  DueDateBannerRow,
  RegulatoryDueDatesService,
} from '../../../../core/services/regulatory-due-dates.service';
import { DueDateBannerComponent } from './regulatory/due-date-banner/due-date-banner.component';

interface FamilyGroup {
  family: string;
  familyLabel: string;
  reports: TenantReportConfigRow[];
}

/**
 * ReportKey → tenant-portal route. Kept in one place so the hub can
 * light up as a router link once a report page ships; anything not
 * listed here still shows in the hub as a plain label ("landing but
 * no page yet"). Phase 21 adds the IFRS 17 pair.
 */
const REPORT_ROUTES: Record<string, string> = {
  IBNR_TRIANGLE: '/tenant/finance/reports/actuarial/ibnr-triangle',
  LOSS_TRIANGLE: '/tenant/finance/reports/actuarial/loss-triangle',
  PERSISTENCY_STUDY: '/tenant/finance/reports/actuarial/persistency-study',
  LAPSE_STUDY: '/tenant/finance/reports/actuarial/lapse-study',
  MORTALITY_STUDY: '/tenant/finance/reports/actuarial/mortality-study',
  MORBIDITY_STUDY: '/tenant/finance/reports/actuarial/morbidity-study',
  IFRS17_LRC_LIC_RECONCILIATION: '/tenant/finance/reports/ifrs17/lrc-lic-reconciliation',
  IFRS17_INSURANCE_REVENUE_SERVICE_RESULT: '/tenant/finance/reports/ifrs17/insurance-revenue-service-result',
};

/**
 * Landing hub at /tenant/finance/reports. Shows every report the tenant
 * has *enabled*, grouped by family. Per-report detail pages are wired
 * as later phases build them; until then each card is informational.
 *
 * <p>Phase 0 ships the skeleton — no per-report routes exist yet, so
 * clicking a card just shows its label. Phases 2-19 replace the label
 * with a routerLink to the actual report page.
 */
@Component({
  selector: 'app-reports-hub',
  standalone: true,
  imports: [CommonModule, RouterModule, IconComponent, SkeletonComponent, DueDateBannerComponent],
  templateUrl: './reports-hub.component.html',
  styleUrl: './reports-hub.component.scss',
})
export class ReportsHubComponent implements OnInit {
  loading = false;
  errorMessage: string | null = null;
  groups: FamilyGroup[] = [];
  totalEnabled = 0;
  private banners = new Map<string, DueDateBannerRow>();

  constructor(
    private reportConfig: TenantReportConfigService,
    private tenantService: TenantService,
    private dueDates: RegulatoryDueDatesService,
  ) {}

  ngOnInit(): void {
    this.load();
  }

  private load(): void {
    const tenantId = this.tenantService.getTenantId();
    if (!tenantId) {
      this.errorMessage = 'No active tenant context';
      return;
    }
    this.loading = true;
    this.errorMessage = null;
    // Fetch banner rows in parallel with the catalogue; if the banner call
    // fails we fall through with an empty map so the hub still renders.
    forkJoin({
      rows: this.reportConfig.list(tenantId),
      banners: this.dueDates.list().pipe(catchError(() => of([] as DueDateBannerRow[]))),
    }).subscribe({
      next: ({ rows, banners }) => {
        const enabled = rows.filter(r => r.enabled);
        this.totalEnabled = enabled.length;
        this.groups = this.groupByFamily(enabled);
        this.banners = new Map(banners.map(b => [b.reportKey, b]));
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Could not load report catalogue.';
        this.loading      = false;
      },
    });
  }

  /** Route for a given report key, or null when the page hasn't shipped
   *  yet — the hub falls back to a plain label in that case. */
  routeFor(reportKey: string): string | null {
    return REPORT_ROUTES[reportKey] ?? null;
  }

  /** Server-computed due-date banner for the report, or null when the
   *  report is not a Phase-16 regulator key for this tenant. */
  bannerFor(reportKey: string): DueDateBannerRow | null {
    return this.banners.get(reportKey) ?? null;
  }

  private groupByFamily(rows: TenantReportConfigRow[]): FamilyGroup[] {
    const byKey = new Map<string, FamilyGroup>();
    for (const row of rows) {
      const familyKey = row.family ?? 'OTHER';
      const familyLabel = row.familyLabel ?? 'Other';
      let group = byKey.get(familyKey);
      if (!group) {
        group = { family: familyKey, familyLabel, reports: [] };
        byKey.set(familyKey, group);
      }
      group.reports.push(row);
    }
    return Array.from(byKey.values());
  }
}
