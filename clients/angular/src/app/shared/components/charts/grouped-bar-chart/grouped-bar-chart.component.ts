import { Component, Input } from '@angular/core';
import { NgxChartsModule, Color, LegendPosition } from '@swimlane/ngx-charts';
import { MASCA_LEGACY_PALETTE } from '../chart-colors';

/**
 * Multi-series grouped bar chart — wraps ngx-charts-bar-vertical-2d.
 *
 * Used by the tenant dashboard's Claims tab to mirror the legacy
 * Masca-Claims-Admin ClaimsGraph: one bar per active currency per month.
 * Single-currency tenants render a single-series chart automatically;
 * multi-currency tenants get one bar group per code in the legend, ordered
 * with the tenant's default currency first (set up server-side).
 */
@Component({
  selector: 'app-grouped-bar-chart',
  standalone: true,
  imports: [NgxChartsModule],
  templateUrl: './grouped-bar-chart.component.html',
  styleUrl: './grouped-bar-chart.component.scss',
})
export class GroupedBarChartComponent {
  /**
   * Multi-series data, e.g.
   * `[{ name: 'USD', series: [{ name: 'Jan', value: 5 }, ...] }, ...]`.
   */
  @Input() data: Array<{ name: string; series: Array<{ name: string; value: number }> }> = [];
  @Input() xAxisLabel = '';
  @Input() yAxisLabel = '';
  @Input() legend = true;
  @Input() legendTitle = '';
  @Input() gradient = true;
  @Input() colorScheme: Color = MASCA_LEGACY_PALETTE;
  /** Default below — matches the legacy MASCA dashboards' chart.js convention. */
  @Input() legendPosition: LegendPosition = LegendPosition.Below;
}
