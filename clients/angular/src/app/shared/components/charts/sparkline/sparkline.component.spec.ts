import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { SparklineComponent } from './sparkline.component';

// ngx-charts binds internal @animationState synthetic props regardless of
// [animations]="false" on the parent chart — a no-op animation provider is
// still required in the test harness or Angular throws NG05105.
function setup() {
  TestBed.configureTestingModule({
    imports: [SparklineComponent],
    providers: [provideNoopAnimations()],
  });
}

describe('SparklineComponent', () => {
  it('renders a single-series line chart bound to the input data', () => {
    setup();
    const fixture = TestBed.createComponent(SparklineComponent);
    fixture.componentInstance.data = Array.from({ length: 12 }, (_, i) => ({
      name: `2026-${String(i + 1).padStart(2, '0')}-01`,
      value: 0.5 + i * 0.02,
    }));
    fixture.componentInstance.height = 60;
    fixture.detectChanges();

    const wrapper: HTMLElement = fixture.nativeElement.querySelector('.sparkline-wrap');
    expect(wrapper).toBeTruthy();
    expect(wrapper.style.height).toBe('60px');
    expect(fixture.nativeElement.querySelector('ngx-charts-line-chart')).toBeTruthy();
  });

  it('wraps the raw points into a single named series', () => {
    setup();
    const fixture = TestBed.createComponent(SparklineComponent);
    fixture.componentInstance.data = [
      { name: 'Jan', value: 1 },
      { name: 'Feb', value: 2 },
    ];
    fixture.componentInstance.seriesName = 'Loss ratio';
    fixture.detectChanges();

    const wrapped = fixture.componentInstance.wrapped();
    expect(wrapped.length).toBe(1);
    expect(wrapped[0].name).toBe('Loss ratio');
    expect(wrapped[0].series).toEqual([
      { name: 'Jan', value: 1 },
      { name: 'Feb', value: 2 },
    ]);
  });
});
