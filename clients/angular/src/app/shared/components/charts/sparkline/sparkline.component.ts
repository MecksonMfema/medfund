import { ChangeDetectionStrategy, Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Color, NgxChartsModule, ScaleType } from '@swimlane/ngx-charts';

/**
 * Compact single-series line chart with no axes / legend / labels — used for
 * KPI tile trend indicators (Phase 7). Wraps {@code ngx-charts-line-chart}
 * with fixed sparkline defaults so callers only pass {@code data} and
 * (optionally) {@code height}.
 *
 * <p>{@code [animations]="false"} is mandatory — the entire codebase disables
 * chart animations for measurable render + test consistency (see
 * {@code line-chart.component.html}, {@code area-chart.component.html}, …).
 */

const SPARKLINE_INDIGO: Color = {
  name: 'SparklineIndigo',
  selectable: true,
  group: ScaleType.Ordinal,
  domain: ['#4f46e5'],
};

export interface SparklinePoint {
  name:  string;
  value: number;
}

@Component({
  selector: 'app-sparkline',
  standalone: true,
  imports: [CommonModule, NgxChartsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="sparkline-wrap" [style.height.px]="height">
      <ngx-charts-line-chart
        [results]="wrapped()"
        [xAxis]="false"
        [yAxis]="false"
        [showXAxisLabel]="false"
        [showYAxisLabel]="false"
        [legend]="false"
        [autoScale]="true"
        [roundDomains]="false"
        [animations]="false"
        [scheme]="scheme">
      </ngx-charts-line-chart>
    </div>
  `,
  styles: [`
    :host { display: block; width: 100%; }
    .sparkline-wrap { width: 100%; }
    .sparkline-wrap ::ng-deep ngx-charts-line-chart { width: 100%; height: 100%; }
    .sparkline-wrap ::ng-deep .line-chart { padding: 0; }
  `],
})
export class SparklineComponent {
  @Input() data: SparklinePoint[] = [];
  @Input() seriesName = 'Trend';
  @Input() height = 60;
  @Input() scheme: Color = SPARKLINE_INDIGO;

  wrapped(): { name: string; series: SparklinePoint[] }[] {
    return [{ name: this.seriesName, series: this.data }];
  }
}
