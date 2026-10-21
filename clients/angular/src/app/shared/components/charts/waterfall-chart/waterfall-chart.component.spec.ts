import { WaterfallChartComponent, WaterfallMovement } from './waterfall-chart.component';
import { SimpleChange } from '@angular/core';

describe('WaterfallChartComponent', () => {
  let cmp: WaterfallChartComponent;

  beforeEach(() => {
    cmp = new WaterfallChartComponent();
  });

  function apply(movements: WaterfallMovement[]) {
    cmp.movements = movements;
    cmp.ngOnChanges({ movements: new SimpleChange(null, movements, true) });
  }

  it('anchors render as a single Up bar at their absolute value', () => {
    apply([
      { name: 'Opening', value: 100, isAnchor: true },
    ]);
    const risers = cmp.stackData.find(s => s.name === 'Riser')!.series;
    const ups = cmp.stackData.find(s => s.name === 'Increase')!.series;
    const downs = cmp.stackData.find(s => s.name === 'Decrease')!.series;
    expect(risers[0]).toEqual({ name: 'Opening', value: 0 });
    expect(ups[0]).toEqual({ name: 'Opening', value: 100 });
    expect(downs[0]).toEqual({ name: 'Opening', value: 0 });
  });

  it('positive delta lifts the delta bar via a Riser at the previous total', () => {
    apply([
      { name: 'Opening', value: 100, isAnchor: true },
      { name: 'New business', value: 30 },
    ]);
    const risers = cmp.stackData.find(s => s.name === 'Riser')!.series;
    const ups = cmp.stackData.find(s => s.name === 'Increase')!.series;
    // Riser lifts the +30 bar to sit on top of the running total (100).
    expect(risers[1]).toEqual({ name: 'New business', value: 100 });
    expect(ups[1]).toEqual({ name: 'New business', value: 30 });
  });

  it('negative delta stacks a Decrease bar down from the previous total', () => {
    apply([
      { name: 'Opening', value: 100, isAnchor: true },
      { name: 'Revenue', value: -40 },
    ]);
    const risers = cmp.stackData.find(s => s.name === 'Riser')!.series;
    const downs = cmp.stackData.find(s => s.name === 'Decrease')!.series;
    // Down bar of height 40 sits on a riser of height 60 (the new running total).
    expect(risers[1]).toEqual({ name: 'Revenue', value: 60 });
    expect(downs[1]).toEqual({ name: 'Revenue', value: 40 });
  });

  it('closing anchor snaps to its absolute value regardless of prior running total', () => {
    apply([
      { name: 'Opening', value: 100, isAnchor: true },
      { name: 'Delta', value: 25 },
      { name: 'Closing', value: 125, isAnchor: true },
    ]);
    const ups = cmp.stackData.find(s => s.name === 'Increase')!.series;
    const risers = cmp.stackData.find(s => s.name === 'Riser')!.series;
    // Closing anchor renders as its own solid bar at 125 from zero.
    expect(ups[2]).toEqual({ name: 'Closing', value: 125 });
    expect(risers[2]).toEqual({ name: 'Closing', value: 0 });
  });

  it('null / non-finite values coerce to zero without crashing', () => {
    apply([
      { name: 'Opening', value: NaN, isAnchor: true },
      { name: 'Delta', value: undefined as unknown as number },
    ]);
    const ups = cmp.stackData.find(s => s.name === 'Increase')!.series;
    const risers = cmp.stackData.find(s => s.name === 'Riser')!.series;
    expect(ups[0].value).toBe(0);
    expect(risers[1].value).toBe(0);
  });
});
