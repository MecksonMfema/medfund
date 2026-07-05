import { BILLING_ROUTES } from './billing.routes';

/**
 * Guards billing route wiring that's easy to break silently. Kept as a
 * pure-structural inspection (no RouterTestingModule) — for a redirect
 * we don't need the router runtime; we just need the entry to still be
 * there and still point at the right target.
 */
describe('BILLING_ROUTES', () => {
  it('preserves the /group-charge → /charge-preview redirect', () => {
    // A silent removal of this entry 404s every bookmark, email link,
    // and audit-log deep link created before the rename. Assert it
    // stays in place with pathMatch: 'full' so a partial-URL match
    // doesn't accidentally send /group-charge/anything through.
    const legacy = BILLING_ROUTES.find(r => r.path === 'group-charge');
    expect(legacy).withContext('legacy /group-charge route must exist').toBeDefined();
    expect(legacy?.redirectTo).toBe('charge-preview');
    expect(legacy?.pathMatch)
      .withContext("redirect must be pathMatch:'full' — otherwise " +
                   "/group-charge/x would silently redirect too")
      .toBe('full');
  });

  it('exposes /charge-preview as the current entry point', () => {
    // The redirect is meaningless if the target doesn't resolve.
    // Guards against a rename regressing both sides simultaneously.
    const current = BILLING_ROUTES.find(r => r.path === 'charge-preview');
    expect(current).withContext('/charge-preview route must exist').toBeDefined();
    expect(current?.data?.['title']).toBe('Charge Preview');
    // fullbleed is what makes the toolbar strip sit flush with the
    // sidebar like the rest of the billing pages — a regression here
    // reintroduces the inset gutter.
    expect(current?.data?.['fullbleed']).toBeTrue();
  });

  it('preserves the legacy /statements → /ledger redirect', () => {
    // Companion guard — same class of "bookmark rot" concern. Cheap to
    // include here so a wholesale redirect purge fails loudly.
    const legacy = BILLING_ROUTES.find(r => r.path === 'statements');
    expect(legacy?.redirectTo).toBe('ledger');
    expect(legacy?.pathMatch).toBe('full');
  });
});
