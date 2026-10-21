import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';

/**
 * Phase 12 §A legacy retrofit — planned surface for admins to populate
 * writtenPremium + currency + boundAt + coverageStart/End + portfolio + cohort
 * on policy rows the V102 backfill marked as {@code LEGACY_NO_PREMIUM}.
 *
 * <p>Current stub renders instructions only. The per-line retrofit tables
 * (LifePolicy, FuneralPolicy, DisabilityPolicy, Vehicle, Property) are
 * deferred to Phase 3b when the LEGACY_NO_PREMIUM query endpoints ship
 * on user-service. The status-flip on save is a two-step server flow:
 * PUT policy with underwriting fields → PATCH status back to 'active' →
 * {@code PolicyIssuedPublisher} fires from the update() path.
 */
@Component({
  selector: 'app-legacy-retrofit',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <header class="page-header">
      <h1>Legacy policy retrofit</h1>
      <p class="page-sub">
        Populate the underwriting fields on policy rows that the V102 backfill
        flagged as <code>LEGACY_NO_PREMIUM</code>. Filling <code>writtenPremium</code>,
        <code>currency</code>, <code>boundAt</code>, <code>coverageStart/End</code>,
        <code>portfolio</code>, and <code>cohort</code> makes them earning-eligible.
      </p>
    </header>

    <div class="banner banner-info">
      This surface is a Phase 3b deliverable. Use the per-line
      <em>Life / Funeral / Disability / Vehicle / Property policies</em> pages to
      edit each row directly for now. The underwriting fields are wired end-to-end
      into the update form.
    </div>
  `,
})
export class LegacyRetrofitComponent {}
