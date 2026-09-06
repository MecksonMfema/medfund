import { Component } from '@angular/core';
import { ActuarialTriangleComponent } from './actuarial-triangle.component';

/**
 * Phase 14 §Actuarial Phase 10 — LOSS_TRIANGLE report page. Thin wrapper
 * around {@link ActuarialTriangleComponent} with the {@code LOSS_TRIANGLE}
 * report key + page copy. IBNR and LOSS share the same compute pipeline;
 * the split is on {@code ReportKey}, not on payload shape.
 */
@Component({
  selector: 'app-loss-triangle',
  standalone: true,
  imports: [ActuarialTriangleComponent],
  template: `
    <app-actuarial-triangle
      reportKey="LOSS_TRIANGLE"
      pageTitle="Loss triangle"
      pageSubtitle="Cumulative loss development by accident + development period. Same async compute pathway as IBNR: swap the shape between paid, incurred, and reported to compare development patterns."
    ></app-actuarial-triangle>
  `,
})
export class LossTriangleComponent {}
