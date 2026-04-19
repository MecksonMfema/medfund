import { Component, Input } from '@angular/core';
import { NgxChartsModule, Color } from '@swimlane/ngx-charts';
import { OCEAN_BREEZE_SCHEME } from '../chart-colors';

@Component({
  selector: 'app-area-chart',
  standalone: true,
  imports: [NgxChartsModule],
  templateUrl: './area-chart.component.html',
  styleUrl: './area-chart.component.scss',
})
export class AreaChartComponent {
  @Input() data: any[] = [];
  @Input() xAxisLabel = '';
  @Input() yAxisLabel = '';
  @Input() legend = false;
  @Input() legendTitle = '';
  @Input() colorScheme: Color = OCEAN_BREEZE_SCHEME;
  @Input() yScaleMax: number | undefined = undefined;
  /** Explicit list of x-axis tick values to display. Empty array means show all ticks. */
  @Input() xAxisTicks: any[] = [];

  // Only render whole-number labels — fractional ticks (0.5, 1.5 …) get an
  // empty string so duplicated rounded values never appear on the y-axis.
  readonly yTickFormat = (value: number): string =>
    Number.isInteger(value) ? value.toString() : '';
}
