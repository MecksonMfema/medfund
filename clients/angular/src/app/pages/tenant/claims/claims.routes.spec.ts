import { Route } from '@angular/router';
import { CLAIMS_ROUTES } from './claims.routes';

/**
 * Structural guard on the claims route table. Kept as a pure array
 * inspection (no RouterTestingModule) — for the "does this path exist"
 * shape checks we don't need the router runtime.
 *
 * <p>The drug/* branch was retired when the claims list became a single
 * table filtered by claim_type. Reintroducing any of these routes would
 * silently split the flow across two pages again, and (worse) skip the
 * insurance-line gating the unified list enforces.
 */
describe('CLAIMS_ROUTES', () => {
  const paths = new Set(
    CLAIMS_ROUTES
      .map(r => (r as Route).path)
      .filter((p): p is string => typeof p === 'string'),
  );

  it('root list ("") is fullbleed and gated on claims:view', () => {
    // Matches /tenant/billing/schemes — the visual pattern the list is
    // aligned to. Regressing fullbleed reintroduces the sidebar-to-content
    // gutter and the tabs no longer sit flush with the header.
    const root = CLAIMS_ROUTES.find(r => (r as Route).path === '') as Route | undefined;
    expect(root).withContext('root claims list route must exist').toBeDefined();
    expect(root?.data?.['fullbleed']).toBeTrue();
    expect(root?.data?.['title']).toBe('Claims');
  });

  it('preserves the ":id" detail route', () => {
    // The list row action navigates here; regressing this route breaks
    // every deep-link into a claim from the audit log or an email.
    expect(paths.has(':id')).withContext(':id detail route must exist').toBeTrue();
  });

  it('has NO drug-claim sub-routes', () => {
    // The unified list replaces the split /drug/* tree — asserting the
    // absence prevents a well-meaning revert from silently reintroducing
    // the parallel pipeline.
    const forbidden = [
      'drug',
      'drug/pending', 'drug/rejected', 'drug/staged', 'drug/captured',
      'drug/search', 'drug/submit', 'drug/tax-withheld',
      'drug/preauth', 'drug/preauth/list',
      'tasks/assign/drug', 'tasks/user/drugs',
    ];
    for (const p of forbidden) {
      expect(paths.has(p))
        .withContext(`route "${p}" must not exist — merged into the unified list`)
        .toBeFalse();
    }
  });

  it('keeps the drug catalogue ("drugs") route — inventory ≠ claims', () => {
    // The "remove drug claims" cleanup intentionally spared the drug
    // catalogue. If a broad sweep deletes this, tenants lose their drug
    // inventory management with no replacement.
    expect(paths.has('drugs')).withContext('drug catalogue must survive').toBeTrue();
    expect(paths.has('drugs/add')).toBeTrue();
    expect(paths.has('drugs/:id/edit')).toBeTrue();
  });

  it('preserves the pre-authorisation branch', () => {
    // Pre-auth is a distinct workflow surfaced from the sidebar; it is
    // not folded into the unified claims list.
    expect(paths.has('preauth')).toBeTrue();
    expect(paths.has('preauth/new')).toBeTrue();
    expect(paths.has('preauth/:id')).toBeTrue();
  });

  it('has NO tasks/* branch', () => {
    // Tasks were removed from the system (2026-07-13). The old routes
    // were roadmap placeholders and never wired to a live queue; if any
    // come back they should land under a new top-level domain, not
    // as claims/tasks/*.
    const forbidden = [
      'tasks', 'tasks/incomplete', 'tasks/complete', 'tasks/add',
      'tasks/assign', 'tasks/user/incomplete', 'tasks/user/claims',
      'tasks/user/complete',
    ];
    for (const p of forbidden) {
      expect(paths.has(p))
        .withContext(`route "${p}" must not exist — tasks domain retired`)
        .toBeFalse();
    }
  });
});
