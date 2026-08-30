import { CommonModule } from '@angular/common';
import { Component, Input, OnChanges, SimpleChanges } from '@angular/core';
import { Color, LegendPosition, NgxChartsModule, ScaleType } from '@swimlane/ngx-charts';

/**
 * One movement in a waterfall: a bar between the running balance and
 * the running balance plus {@link value}. Negative {@link value} draws
 * a downward bar; the two anchor bars ({@link isAnchor}=true) draw the
 * opening and closing totals.
 */
export interface WaterfallMovement {
  name: string;
  value: number;
  /** Set true for the opening and closing bars — they render at their
   *  absolute height from zero rather than as a delta. */
  isAnchor?: boolean;
}

interface StackSeries {
  name: string;
  series: { name: string; value: number }[];
}

/**
 * IFRS 17 movement waterfall (Phase 15 §21). Renders one bar per movement
 * step: opening balance (anchor) → deltas (new business, cash flows,
 * revenue release, finance expense, RA release, CSM release, …) → closing
 * (anchor). Every step is aligned on the x-axis by {@link WaterfallMovement.name}.
 *
 * <p>ngx-charts has no native waterfall, so we build it out of the
 * {@code ngx-charts-bar-vertical-stacked} primitive with two series:
 * a transparent "riser" that lifts each delta bar to the running total,
 * and the delta bar itself coloured green for positive / red for
 * negative. Anchor bars render as a single solid bar from zero (their
 * "riser" is zero).
 */
@Component({
  selector: 'app-waterfall-chart',
  standalone: true,
  imports: [CommonModule, NgxChartsModule],
  template: `
    <ngx-charts-bar-vertical-stacked
      [results]="stackData"
      [xAxis]="true"
      [yAxis]="true"
      [showXAxisLabel]="!!xAxisLabel"
      [showYAxisLabel]="!!yAxisLabel"
      [xAxisLabel]="xAxisLabel"
      [yAxisLabel]="yAxisLabel"
      [scheme]="scheme"
      [gradient]="false"
      [legend]="false"
      [legendPosition]="legendPosition"
      [animations]="false"
      [barPadding]="12">
    </ngx-charts-bar-vertical-stacked>
  `,
  styles: [`
    :host { display: block; width: 100%; }
    ::ng-deep ngx-charts-bar-vertical-stacked { display: block; }
  `],
})
export class WaterfallChartComponent implements OnChanges {
  @Input() movements: WaterfallMovement[] = [];
  @Input() xAxisLabel = 'Movement';
  @Input() yAxisLabel = 'Amount';
  /** Accepted for API symmetry with the sibling {@code LineChartComponent},
   *  but not forwarded to ngx-charts — the stacked-bar auto-sizes to
   *  its container which matches the line-chart precedent. Callers can
   *  still set it to document intent; the actual height comes from the
   *  container's CSS. */
  @Input() view: [number | undefined, number] | undefined = undefined;

  legendPosition: LegendPosition = LegendPosition.Right;
  stackData: StackSeries[] = [];
  scheme: Color = this.buildScheme();

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['movements']) {
      this.stackData = this.buildStack(this.movements ?? []);
      this.scheme = this.buildScheme();
    }
  }

  /**
   * Transform ordered movements into stacked series. Each x-axis bucket
   * gets three stacked segments:
   *   - `Down`   below zero (only for downward deltas)
   *   - `Up`     above zero (positive deltas + anchors)
   *   - `Riser`  transparent connector lifting the bar to the running total
   *
   * The Riser is stacked *first* so it lands under the visible bar; for
   * downward deltas we invert the ordering so the delta sits between the
   * previous-total anchor and the new (lower) running total.
   */
  private buildStack(movements: WaterfallMovement[]): StackSeries[] {
    const risers: { name: string; value: number }[] = [];
    const ups:    { name: string; value: number }[] = [];
    const downs:  { name: string; value: number }[] = [];

    let running = 0;
    for (const m of movements) {
      // Coerce NaN / Infinity / undefined to 0 so a bad producer never
      // yields a broken chart — matches the numeric-normalisation the
      // page components do when reading the aggregator envelope.
      const raw = m.value ?? 0;
      const v = Number.isFinite(raw) ? raw : 0;
      if (m.isAnchor) {
        // Anchor: render as a single bar from zero to its absolute value.
        // Running balance snaps to the anchor value.
        risers.push({ name: m.name, value: 0 });
        ups.push({ name: m.name, value: v > 0 ? v : 0 });
        downs.push({ name: m.name, value: v < 0 ? Math.abs(v) : 0 });
        running = v;
      } else if (v >= 0) {
        // Positive delta: riser lifts the bar to the previous running
        // total; the delta itself sits on top.
        risers.push({ name: m.name, value: running });
        ups.push({ name: m.name, value: v });
        downs.push({ name: m.name, value: 0 });
        running += v;
      } else {
        // Negative delta: riser lifts to the *new* running total (which
        // is lower); the down bar hangs between new and prior totals.
        running += v;
        risers.push({ name: m.name, value: running });
        ups.push({ name: m.name, value: 0 });
        downs.push({ name: m.name, value: Math.abs(v) });
      }
    }

    return [
      { name: 'Riser', series: risers },
      { name: 'Increase', series: ups },
      { name: 'Decrease', series: downs },
    ];
  }

  /**
   * Fixed 3-colour scheme: transparent riser, green up, red down. Kept as
   * an explicit palette so the transparent riser doesn't inherit a chart
   * theme colour and become visible.
   */
  private buildScheme(): Color {
    return {
      name: 'ifrs17-waterfall',
      selectable: false,
      group: ScaleType.Ordinal,
      domain: ['rgba(0,0,0,0)', '#2EC4B6', '#E71D36'],
    };
  }
}
