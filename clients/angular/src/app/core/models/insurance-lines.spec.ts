import {
  parseInsuranceLines,
  parseProviderRegLabel,
  parseDrugClaimsEnabled,
  parseSchemeTerminology,
  DEFAULT_SCHEME_TERMINOLOGY,
  deriveProviderRegLabel,
  insuranceLineLabel,
  schemeTypesForLines,
  INSURANCE_LINES,
  PERSON_CENTRIC_LINES,
  isPersonCentricLine,
  LINE_FOR_SCHEME_TYPE,
  lineForSchemeType,
  SCHEME_TYPES_BY_LINE,
} from './insurance-lines';

describe('insurance-lines parsers', () => {
  describe('parseInsuranceLines', () => {
    it('returns [] for null, undefined, and empty settings', () => {
      expect(parseInsuranceLines(null)).toEqual([]);
      expect(parseInsuranceLines(undefined)).toEqual([]);
      expect(parseInsuranceLines('')).toEqual([]);
      expect(parseInsuranceLines('{}')).toEqual([]);
    });

    it('returns [] for malformed JSON without throwing', () => {
      expect(parseInsuranceLines('{not json')).toEqual([]);
      expect(parseInsuranceLines('null')).toEqual([]);
    });

    it('returns [] when insuranceLines is missing or not an array', () => {
      expect(parseInsuranceLines('{"other":"value"}')).toEqual([]);
      expect(parseInsuranceLines('{"insuranceLines":"HEALTH"}')).toEqual([]);
      expect(parseInsuranceLines('{"insuranceLines":null}')).toEqual([]);
    });

    it('returns the configured lines preserving order', () => {
      const json = '{"insuranceLines":["LIFE","HEALTH","VEHICLE"]}';
      expect(parseInsuranceLines(json)).toEqual(['LIFE', 'HEALTH', 'VEHICLE']);
    });
  });

  describe('parseProviderRegLabel', () => {
    it('returns empty string for null / empty / malformed inputs', () => {
      expect(parseProviderRegLabel(null)).toBe('');
      expect(parseProviderRegLabel('{}')).toBe('');
      expect(parseProviderRegLabel('{not json')).toBe('');
    });

    it('returns providerRegLabel when present', () => {
      expect(parseProviderRegLabel('{"providerRegLabel":"FSP Reg"}')).toBe('FSP Reg');
    });

    it('returns empty string when key is missing', () => {
      expect(parseProviderRegLabel('{"other":"value"}')).toBe('');
    });
  });

  describe('parseDrugClaimsEnabled', () => {
    it('defaults to true for null, empty, and {} settings', () => {
      expect(parseDrugClaimsEnabled(null)).toBe(true);
      expect(parseDrugClaimsEnabled(undefined)).toBe(true);
      expect(parseDrugClaimsEnabled('{}')).toBe(true);
    });

    it('defaults to true for malformed JSON', () => {
      expect(parseDrugClaimsEnabled('{broken')).toBe(true);
    });

    it('honours an explicit false', () => {
      expect(parseDrugClaimsEnabled('{"drugClaimsEnabled":false}')).toBe(false);
    });

    it('honours an explicit true', () => {
      expect(parseDrugClaimsEnabled('{"drugClaimsEnabled":true}')).toBe(true);
    });

    it('coerces truthy values', () => {
      expect(parseDrugClaimsEnabled('{"drugClaimsEnabled":1}')).toBe(true);
      expect(parseDrugClaimsEnabled('{"drugClaimsEnabled":0}')).toBe(false);
    });
  });

  describe('parseSchemeTerminology', () => {
    it('returns the default Scheme / Schemes for null / empty / malformed inputs', () => {
      expect(parseSchemeTerminology(null)).toEqual(DEFAULT_SCHEME_TERMINOLOGY);
      expect(parseSchemeTerminology('{}')).toEqual(DEFAULT_SCHEME_TERMINOLOGY);
      expect(parseSchemeTerminology('{not json')).toEqual(DEFAULT_SCHEME_TERMINOLOGY);
    });

    it('reads the configured terminology', () => {
      const json = '{"schemeLabelSingular":"Plan","schemeLabelPlural":"Plans"}';
      expect(parseSchemeTerminology(json)).toEqual({ singular: 'Plan', plural: 'Plans' });
    });

    it('falls back to the default when one half is missing', () => {
      expect(parseSchemeTerminology('{"schemeLabelSingular":"Policy"}'))
        .toEqual({ singular: 'Policy', plural: DEFAULT_SCHEME_TERMINOLOGY.plural });
    });
  });

  describe('deriveProviderRegLabel', () => {
    it('returns the generic label when no lines are configured', () => {
      expect(deriveProviderRegLabel([])).toBe('Registration / AHFOZ / Licence Number');
    });

    it("returns the first matching line's registrationLabel", () => {
      expect(deriveProviderRegLabel(['HEALTH'])).toBe('AHFOZ / Practice Number');
      expect(deriveProviderRegLabel(['VEHICLE', 'HEALTH'])).toBe('Workshop Licence Number');
    });

    it('skips unknown lines until it finds a match', () => {
      expect(deriveProviderRegLabel(['__missing__', 'LIFE'])).toBe('FSP / Financial Services Reg. Number');
    });

    it('returns the generic label when no line matches the catalogue', () => {
      expect(deriveProviderRegLabel(['ZZZ', 'UNKNOWN'])).toBe('Registration / AHFOZ / Licence Number');
    });
  });

  describe('insuranceLineLabel', () => {
    it('returns the catalogue label for a known line', () => {
      expect(insuranceLineLabel('HEALTH')).toBe('Health Insurance');
    });

    it('returns the raw value for an unknown line — fail-open, never blank', () => {
      expect(insuranceLineLabel('XYZ')).toBe('XYZ');
    });
  });

  describe('schemeTypesForLines', () => {
    it('falls back to HEALTH when lines is empty', () => {
      const out = schemeTypesForLines([]);
      expect(out.length).toBeGreaterThan(0);
      expect(out.every(o => o.line === 'HEALTH')).toBe(true);
      expect(out.map(o => o.code)).toContain('medical_aid');
    });

    it('returns only the requested line for a single-line tenant', () => {
      const out = schemeTypesForLines(['VEHICLE']);
      expect(out.every(o => o.line === 'VEHICLE')).toBe(true);
      expect(out.map(o => o.code)).toEqual(['comprehensive', 'third_party', 'fleet']);
    });

    it('preserves line order across the union', () => {
      const out = schemeTypesForLines(['LIFE', 'HEALTH']);
      const lines = out.map(o => o.line);
      const firstHealthIdx = lines.indexOf('HEALTH');
      const lastLifeIdx = lines.lastIndexOf('LIFE');
      expect(lastLifeIdx).toBeLessThan(firstHealthIdx);
    });

    it('silently drops unknown lines without throwing', () => {
      const out = schemeTypesForLines(['__nope__', 'FUNERAL']);
      expect(out.every(o => o.line === 'FUNERAL')).toBe(true);
      expect(out.length).toBeGreaterThan(0);
    });

    it('every catalogue line resolves to at least one option', () => {
      for (const line of INSURANCE_LINES) {
        expect(schemeTypesForLines([line.value]).length).toBeGreaterThan(0);
      }
    });
  });

  describe('isPersonCentricLine', () => {
    it('returns true for person-centric lines', () => {
      for (const line of ['HEALTH', 'LIFE', 'FUNERAL', 'GROUP', 'TRAVEL', 'DISABILITY']) {
        expect(isPersonCentricLine(line)).toBe(true);
      }
    });

    it('returns false for asset-centric lines', () => {
      expect(isPersonCentricLine('VEHICLE')).toBe(false);
      expect(isPersonCentricLine('PROPERTY')).toBe(false);
    });

    it('returns false for unknown / null / undefined inputs', () => {
      expect(isPersonCentricLine(null)).toBe(false);
      expect(isPersonCentricLine(undefined)).toBe(false);
      expect(isPersonCentricLine('')).toBe(false);
      expect(isPersonCentricLine('NOPE')).toBe(false);
    });

    it('PERSON_CENTRIC_LINES covers every non-asset catalogue entry', () => {
      const assetLines = new Set(['VEHICLE', 'PROPERTY']);
      for (const line of INSURANCE_LINES) {
        const expected = !assetLines.has(line.value);
        expect(PERSON_CENTRIC_LINES.has(line.value)).toBe(expected);
      }
    });
  });

  describe('LINE_FOR_SCHEME_TYPE / lineForSchemeType', () => {
    it('maps every code in SCHEME_TYPES_BY_LINE to its declaring line', () => {
      for (const [line, bucket] of Object.entries(SCHEME_TYPES_BY_LINE)) {
        for (const t of bucket) {
          expect(LINE_FOR_SCHEME_TYPE[t.code]).toBe(line);
        }
      }
    });

    it('falls back to HEALTH for unknown / blank / null inputs', () => {
      expect(lineForSchemeType(null)).toBe('HEALTH');
      expect(lineForSchemeType(undefined)).toBe('HEALTH');
      expect(lineForSchemeType('')).toBe('HEALTH');
      expect(lineForSchemeType('__nope__')).toBe('HEALTH');
    });

    it('returns the correct line for sample known codes', () => {
      expect(lineForSchemeType('medical_aid')).toBe('HEALTH');
      expect(lineForSchemeType('comprehensive')).toBe('VEHICLE');
      expect(lineForSchemeType('term_life')).toBe('LIFE');
      expect(lineForSchemeType('group_disability')).toBe('GROUP');
      expect(lineForSchemeType('buildings')).toBe('PROPERTY');
    });
  });
});
