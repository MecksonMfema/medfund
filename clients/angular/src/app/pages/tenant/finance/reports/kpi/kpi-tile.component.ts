import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, Component, EventEmitter, Input, Output } from '@angular/core';
import { Router, RouterModule } from '@angular/router';
import {
  SparklineComponent,
  SparklinePoint,
} from '../../../../../shared/components/charts/sparkline/sparkline.component';
import { KpiKey, KpiValue } from '../../../../../core/services/executive-kpi.service';

/**
 * Compact card summarising one executive KPI (Phase 7). Renders the
 * reporting-currency composite ratio, a native-currency chip strip, a 12-month
 * sparkline, and drill-through navigation to the relevant detail report on
 * click. Warnings from the underlying envelope (FX misses, peer failure) show
 * inline at the bottom of the card.
 *
 * <p>The mixed-basis note for COMBINED_RATIO is disclosed via a small
 * {@code ⓘ} affordance next to the tile heading — the native {@code title}
 * attribute is enough (no tooltip module is wired platform-wide yet).
 */
@Component({
  selector: 'app-kpi-tile',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CommonModule, RouterModule, SparklineComponent],
  templateUrl: './kpi-tile.component.html',
  styleUrl: './kpi-tile.component.scss',
})
export class KpiTileComponent {
  @Input({ required: true }) key!: KpiKey;
  @Input({ required: true }) label!: string;
  @Input() composite:      number | null = null;
  @Input() basisNote:      string | null = null;
  @Input() perCurrency:    Record<string, KpiValue> = {};
  @Input() sparklineData:  SparklinePoint[] = [];
  @Input() warnings:       string[] = [];
  @Input() periodLabel     = '';
  @Input() trendDirection: 'up' | 'down' | 'flat' = 'flat';
  @Input() loading         = false;
  @Input() exporting       = false;

  /** Emitted when the user clicks the per-tile "Export XLSX" button. The
   *  dashboard owns the download plumbing so tile stays pure UI. */
  @Output() exportRequested = new EventEmitter<KpiKey>();

  /**
   * Drill-through targets. COMBINED_RATIO stays on the KPI page — the mix of
   * loss and expense makes any single detail page misleading, so the tile
   * scrolls to itself (no navigation) via a hash anchor.
   *
   * <p>Loss ratio detail is served under {@code /billing-vs-claims} rather
   * than {@code /loss-ratio} — the plan mentions the latter, but the actual
   * route wired in {@code finance.routes.ts} is {@code billing-vs-claims}.
   * Claims frequency + severity share the {@code claims-frequency-severity}
   * report (single page covers both dimensions).
   */
  readonly drillMap: Record<KpiKey, string | null> = {
    LOSS_RATIO_KPI:    '/tenant/finance/reports/billing-vs-claims',
    EXPENSE_RATIO:     '/tenant/finance/reports/commission/statement',
    COMBINED_RATIO:    null,
    CLAIMS_FREQUENCY:  '/tenant/finance/reports/claims-frequency-severity',
    AVERAGE_SEVERITY:  '/tenant/finance/reports/claims-frequency-severity',
  };

  constructor(private router: Router) {}

  /** Native ordered list for the per-currency chip strip. */
  get perCurrencyList(): { currency: string; value: KpiValue }[] {
    return Object.entries(this.perCurrency)
      .map(([currency, value]) => ({ currency, value }))
      .sort((a, b) => a.currency.localeCompare(b.currency));
  }

  get basisTooltip(): string {
    if (this.basisNote === 'MIXED_LOSS_EARNED_EXPENSE_WRITTEN') {
      return 'Mixed basis — loss ratio on earned premium, expense ratio on written premium (NAIC convention).';
    }
    return this.basisNote ?? '';
  }

  get hasDrillTarget(): boolean {
    return !!this.drillMap[this.key];
  }

  onTileClick(): void {
    if (this.loading) return;
    const target = this.drillMap[this.key];
    if (target) this.router.navigateByUrl(target);
  }

  /** Click on the Export button — bubble upstream. Also stops the click
   *  from propagating to the parent tile (which would trigger drill-nav). */
  onExportClick(event: Event): void {
    event.stopPropagation();
    if (this.loading || this.exporting) return;
    this.exportRequested.emit(this.key);
  }
}
