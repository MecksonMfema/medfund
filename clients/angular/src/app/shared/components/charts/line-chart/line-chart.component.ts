import { Component, Input } from '@angular/core';
import { NgxChartsModule, Color, LegendPosition } from '@swimlane/ngx-charts';
import { OCEAN_BREEZE_SCHEME } from '../chart-colors';

@Component({
  selector: 'app-line-chart',
  standalone: true,
  imports: [NgxChartsModule],
  templateUrl: './line-chart.component.html',
  styleUrl: './line-chart.component.scss',
})
export class LineChartComponent {
  @Input() data: any[] = [];
  @Input() xAxisLabel = '';
  @Input() yAxisLabel = '';
  @Input() legend = false;
  @Input() legendTitle = '';
  @Input() gradient = false;
  @Input() colorScheme: Color = OCEAN_BREEZE_SCHEME;
  /** Pass [undefined, height] to let the chart fill its container width. */
  @Input() view: [number | undefined, number] | undefined = undefined;
  /** Where the legend renders relative to the chart — defaults to ngx-charts' Right. */
  @Input() legendPosition: LegendPosition = LegendPosition.Right;
  /** Set false to anchor the y-axis to explicit min/max (chart no longer
   *  starts at the smallest data point). Existing callers keep autoScale. */
  @Input() autoScale = true;
  /** Explicit y-axis minimum, applied when autoScale=false. */
  @Input() yScaleMin: number | undefined = undefined;
  /** Explicit y-axis maximum, applied when autoScale=false. */
  @Input() yScaleMax: number | undefined = undefined;
  /** Optional tick label formatter (locale grouping, unit suffixes, etc.). */
  @Input() yAxisTickFormatting: ((value: number) => string) | undefined = undefined;
}
