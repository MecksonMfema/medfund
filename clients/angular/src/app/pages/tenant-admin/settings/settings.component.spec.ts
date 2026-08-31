import { JURISDICTIONS } from './settings.component';

describe('JURISDICTIONS constant', () => {
  it('exposes the — None — placeholder plus every TenantJurisdiction value', () => {
    // The list must stay in sync with the Java enum
    // (services/java/tenancy-service/.../TenantJurisdiction.java). Server-side
    // validation returns 422 on any code we ship here that the enum doesn't
    // know about — this test guards the intent, the server guards the round-trip.
    const values = JURISDICTIONS.map(j => j.value);
    expect(values).toEqual([
      '',
      'ZW_IPEC_SHORT_TERM',
      'ZW_IPEC_LIFE',
      'ZA_CMS_MEDICAL_SCHEME',
      'ZA_FSCA_SHORT_TERM',
      'ZA_FSCA_LONG_TERM',
      'US_NAIC',
    ]);
  });

  it('renders 7 selectable options — the placeholder plus 6 jurisdictions', () => {
    expect(JURISDICTIONS.length).toBe(7);
  });

  it('every option carries a non-blank label', () => {
    for (const j of JURISDICTIONS) {
      expect(j.label).withContext(`label for value '${j.value}'`).toBeTruthy();
    }
  });

  it('every non-empty value is unique', () => {
    const nonEmpty = JURISDICTIONS.map(j => j.value).filter(v => v !== '');
    expect(new Set(nonEmpty).size).toBe(nonEmpty.length);
  });
});
