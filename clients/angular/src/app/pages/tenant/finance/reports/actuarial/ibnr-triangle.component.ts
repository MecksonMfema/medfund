import { Component } from '@angular/core';
import { ActuarialTriangleComponent } from './actuarial-triangle.component';

/**
 * Phase 14 §Actuarial Phase 10 — IBNR triangle report page. Thin wrapper
 * around {@link ActuarialTriangleComponent} with the {@code IBNR_TRIANGLE}
 * report key + page copy. Every submit is guarded server-side by
 * {@code @RequiresReport(IBNR_TRIANGLE)} so toggling the report off in
 * tenant admin returns 403 without a client change.
 */
@Component({
  selector: 'app-ibnr-triangle',
  standalone: true,
  imports: [ActuarialTriangleComponent],
  template: `
    <app-actuarial-triangle
      reportKey="IBNR_TRIANGLE"
      pageTitle="IBNR triangle"
      pageSubtitle="Chain-ladder projection of ultimate claims from the paid or incurred triangle. Every submit spawns an async compute job — the split-view lights up once ai-service returns the LDFs."
    ></app-actuarial-triangle>
  `,
})
export class IbnrTriangleComponent {}
