import { Component, Input } from '@angular/core';
import { CommonModule, Location } from '@angular/common';
import { Router } from '@angular/router';
import { IconComponent } from '../../../../../shared/components/icon/icon.component';

/**
 * Header "Back" button reused across every finance report page. Prefers
 * browser-history back so the user returns to whatever route brought them
 * here (usually the reports hub, sometimes a sibling report they were
 * drilling from). Falls back to a fixed parent route when history is empty
 * (deep-link entry, refresh, external link) so the user never lands nowhere.
 *
 * <p>Styled with the shared {@code .btn.btn-default} shape used by the
 * reference page {@code /tenant/billing/schemes/:id/benefits} — drop the
 * component into a page-header's {@code .header-actions} slot and it
 * inherits the surrounding button styles.
 */
@Component({
  selector: 'app-report-back-button',
  standalone: true,
  imports: [CommonModule, IconComponent],
  template: `
    <button type="button" class="btn btn-default" (click)="back()" [attr.aria-label]="label">
      <app-icon name="arrow-left" [size]="14"></app-icon>
      {{ label }}
    </button>
  `,
})
export class ReportBackButtonComponent {
  /** Label shown on the button. Defaults to the common "Back to Reports". */
  @Input() label = 'Back to Reports';
  /** Route used when browser history is empty (deep-link / refresh case). */
  @Input() fallback = '/tenant/finance/reports';

  constructor(private location: Location, private router: Router) {}

  back(): void {
    // history.length > 1 means there's something to go back to inside the
    // SPA session. If not, land on the hub so the button always does
    // something visible instead of no-op.
    if (typeof window !== 'undefined' && window.history.length > 1) {
      this.location.back();
    } else {
      this.router.navigateByUrl(this.fallback);
    }
  }
}
