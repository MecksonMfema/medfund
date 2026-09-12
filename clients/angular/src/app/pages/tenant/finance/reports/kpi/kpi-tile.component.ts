import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, Component, EventEmitter, Input, Output } from '@angular/core';
import { Router, RouterModule } from '@angular/router';
import {
  SparklineComponent,
  SparklinePoint,
} from '../../../../../shared/components/charts/sparkline/sparkline.component';
import { IconComponent } from '../../../../../shared/components/icon/icon.component';
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
  imports: [CommonModule, RouterModule, SparklineComponent, IconComponent],
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
  /**
   * Structured warning tokens from the KPI envelope. Two shapes carry:
   *  1. Peer-unavailable tokens ("<callName> unavailable", "commission-aggregate unavailable")
   *  2. Data-quality prose ("IBNR run pending...", "Denominator N below noise threshold...")
   * Both are user-visible; the tile presents them via a single info affordance
   * (see {@link warningLabel} + {@link warningTooltip}) rather than raw
   * bulleted text so the composite ratio stays the focal element.
   */
  @Input() warnings:       string[] = [];
  @Input() periodLabel     = '';
  @Input() trendDirection: 'up' | 'down' | 'flat' = 'flat';
  @Input() loading         = false;
  @Input() exporting       = false;
  /** Display format for the composite + per-currency chips.
   * - `ratio`  → percent (loss / expense / combined / frequency)
   * - `amount` → currency-scoped decimal (average severity is a $ value,
   *   not a ratio; showing it as `%` yields absurd values like 32,550%). */
  @Input() valueFormat: 'ratio' | 'amount' = 'ratio';

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
      return 'Mixed basis: loss ratio on earned premium, expense ratio on written premium (NAIC convention).';
    }
    return this.basisNote ?? '';
  }

  /** True when the tile has anything to caveat — peer failure or data-quality. */
  get hasWarnings(): boolean {
    return this.warnings.length > 0;
  }

  /** Kind of caveat, so the tile styles the icon differently for data quality
   *  vs a peer-side outage. Peer outages get amber; data-quality notes get muted. */
  get warningKind(): 'peer' | 'quality' {
    return this.warnings.some(w => /unavailable$/i.test(w)) ? 'peer' : 'quality';
  }

  /** Short label rendered next to the icon. Kept generic so the tile stays
   *  scannable; the tooltip carries the detail. */
  get warningLabel(): string {
    return this.warningKind === 'peer' ? 'Partial data' : 'Data note';
  }

  /** Tooltip / accessible label. Translates each structured warning token to
   *  operator-friendly copy — never surfaces URLs, HTTP codes or class names. */
  get warningTooltip(): string {
    return this.warnings.map(w => humaniseWarning(w)).join('\n');
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

/**
 * Translate a structured warning token from the KPI envelope to plain
 * operator-facing copy. Never lets URLs, HTTP status codes or class names
 * through — see {@code CrossServiceCallHelper#guarded} which enforces the
 * same discipline server-side, and preserves the invariant even if a new
 * warning source forgets to sanitise.
 */
function humaniseWarning(raw: string): string {
  const w = raw.trim();
  // Peer-unavailable tokens: "<callName> unavailable"
  if (/premium-earned\b.*\bunavailable/i.test(w))
    return 'Earned-premium data is temporarily unavailable.';
  if (/claims-incurred\b.*\bunavailable/i.test(w))
    return 'Claims data is temporarily unavailable.';
  if (/billing-aggregate\b.*\bunavailable/i.test(w))
    return 'Written-premium data is temporarily unavailable.';
  if (/commission-aggregate\b.*\bunavailable/i.test(w))
    return 'Commission data is temporarily unavailable.';
  // Data-quality tokens
  if (/IBNR run pending/i.test(w))
    return 'IBNR reserves are not fresh; showing paid + reserve change only.';
  if (/below noise threshold/i.test(w))
    return 'Sample size is small; ratio may be volatile.';
  if (/ignores insuranceLine filter/i.test(w))
    return 'Line filter does not apply to written premium in this release.';
  // Best-effort fallback: strip anything URL-shaped or status-code-shaped so
  // an unknown source cannot leak internals through the browser.
  return w.replace(/https?:\/\/\S+/gi, '')
          .replace(/\b[45]\d\d\s+[A-Z][A-Za-z ]+/g, '')
          .replace(/\s{2,}/g, ' ')
          .trim() || 'A data source is temporarily unavailable.';
}
