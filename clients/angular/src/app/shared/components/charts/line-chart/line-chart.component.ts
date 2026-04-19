import { Component, Input } from '@angular/core';
import { NgxChartsModule, Color } from '@swimlane/ngx-charts';
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
  @Input() view: [number, number] | undefined = undefined;
}
