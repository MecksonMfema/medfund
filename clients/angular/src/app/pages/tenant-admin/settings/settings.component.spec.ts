import { JURISDICTIONS } from './settings.component';

describe('JURISDICTIONS constant', () => {
  it('exposes the placeholder plus every UI-visible TenantJurisdiction value', () => {
    // SADC-only platform: US_NAIC is deleted from the Java enum and
    // ZA_CMS_MEDICAL_SCHEME is retained on the backend but hidden from
    // this picker so tenants cannot select it. Server-side validation
    // still accepts ZA_CMS_MEDICAL_SCHEME rows already persisted.
    const values = JURISDICTIONS.map(j => j.value);
    expect(values).toEqual([
      '',
      'ZW_IPEC_SHORT_TERM',
      'ZW_IPEC_LIFE',
      'ZA_FSCA_SHORT_TERM',
      'ZA_FSCA_LONG_TERM',
    ]);
  });

  it('renders 5 selectable options: placeholder plus 4 jurisdictions', () => {
    expect(JURISDICTIONS.length).toBe(5);
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
