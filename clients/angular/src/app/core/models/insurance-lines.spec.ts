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
  claimFieldsForLine,
  hasClaimField,
  usesLineItems,
  CLAIM_FIELDS_BY_LINE,
  LINE_ITEM_LINES,
  providerModeForLine,
  PROVIDER_MODE_BY_LINE,
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

  // ── Per-line claim-form field sets ──────────────────────────────────
  //
  // These guard the adaptive-form contract: hiding a field that a line
  // actually needs would silently drop information from the submit
  // payload, and showing a field a line doesn't use would surface an
  // unresolvable required-field error.

  describe('claimFieldsForLine', () => {
    it('HEALTH exposes the full itemised-claim field set', () => {
      const f = claimFieldsForLine('HEALTH');
      expect(f.has('tariffCodes')).toBeTrue();
      expect(f.has('modifiers')).toBeTrue();
      expect(f.has('diagnosisCodes')).toBeTrue();
      expect(f.has('procedureCodes')).toBeTrue();
      // Fields that belong to non-medical lines must not leak in.
      expect(f.has('vehicleRegistration')).toBeFalse();
      expect(f.has('deathCertificate')).toBeFalse();
    });

    it('VEHICLE has incident + registration fields, no tariff or diagnosis', () => {
      const f = claimFieldsForLine('VEHICLE');
      expect(f.has('vehicleRegistration')).toBeTrue();
      expect(f.has('incidentLocation')).toBeTrue();
      expect(f.has('policeReport')).toBeTrue();
      expect(f.has('tariffCodes')).toBeFalse();
      expect(f.has('modifiers')).toBeFalse();
      expect(f.has('diagnosisCodes')).toBeFalse();
    });

    it('FUNERAL requires death certificate + relationship', () => {
      const f = claimFieldsForLine('FUNERAL');
      expect(f.has('deathCertificate')).toBeTrue();
      expect(f.has('deceasedRelationship')).toBeTrue();
      expect(f.has('tariffCodes')).toBeFalse();
    });

    it('PROPERTY carries address + incident; no medical fields', () => {
      const f = claimFieldsForLine('PROPERTY');
      expect(f.has('propertyAddress')).toBeTrue();
      expect(f.has('incidentLocation')).toBeTrue();
      expect(f.has('diagnosisCodes')).toBeFalse();
    });

    it('unknown / null / undefined lines fall back to HEALTH', () => {
      // A brief window during tenant boot has no insurance line — the
      // form must still render something sensible rather than crashing.
      expect(claimFieldsForLine(null)).toBe(CLAIM_FIELDS_BY_LINE['HEALTH']);
      expect(claimFieldsForLine(undefined)).toBe(CLAIM_FIELDS_BY_LINE['HEALTH']);
      expect(claimFieldsForLine('__nope__')).toBe(CLAIM_FIELDS_BY_LINE['HEALTH']);
    });

    it('every configured insurance line has a field set', () => {
      // Cheap coverage guard — every entry in INSURANCE_LINES must
      // resolve to a non-empty set. Adding a new line without a
      // matching entry breaks the form silently.
      for (const line of INSURANCE_LINES) {
        expect(claimFieldsForLine(line.value).size)
          .withContext(`line ${line.value} must have at least one claim field`)
          .toBeGreaterThan(0);
      }
    });
  });

  describe('hasClaimField', () => {
    it('is a thin convenience over the set', () => {
      expect(hasClaimField('HEALTH', 'tariffCodes')).toBeTrue();
      expect(hasClaimField('VEHICLE', 'tariffCodes')).toBeFalse();
      expect(hasClaimField('FUNERAL', 'deathCertificate')).toBeTrue();
    });
  });

  describe('usesLineItems', () => {
    it('true only for lines with an itemised body (HEALTH / GROUP / TRAVEL)', () => {
      expect(usesLineItems('HEALTH')).toBeTrue();
      expect(usesLineItems('GROUP')).toBeTrue();
      expect(usesLineItems('TRAVEL')).toBeTrue();
    });

    it('false for asset / event / benefit lines', () => {
      expect(usesLineItems('VEHICLE')).toBeFalse();
      expect(usesLineItems('PROPERTY')).toBeFalse();
      expect(usesLineItems('FUNERAL')).toBeFalse();
      expect(usesLineItems('LIFE')).toBeFalse();
      expect(usesLineItems('DISABILITY')).toBeFalse();
    });

    it('false for missing / unknown line', () => {
      // Non-itemised is the safer default — the form falls back to the
      // single-total layout instead of rendering an empty FormArray.
      expect(usesLineItems(null)).toBeFalse();
      expect(usesLineItems(undefined)).toBeFalse();
      expect(usesLineItems('__nope__')).toBeFalse();
    });

    it('LINE_ITEM_LINES stays in sync with usesLineItems()', () => {
      for (const line of Array.from(LINE_ITEM_LINES)) {
        expect(usesLineItems(line)).toBeTrue();
      }
    });
  });

  // ── Per-line provider policy ─────────────────────────────────────────
  //
  // The rule directly drives what the submit form asks for. Guard the
  // three cases explicitly so a well-meaning "let's require a provider
  // everywhere" refactor can't silently strand LIFE / DISABILITY
  // captures with a required field they can't satisfy.

  describe('providerModeForLine', () => {
    it('lines that can be provider-paid OR member-reimbursed are OPTIONAL', () => {
      // The distinction between "network payment" and "member paid
      // out-of-pocket then claimed reimbursement" is a per-submission
      // choice, not a per-line rule — every line except the true
      // no-provider payout lines accepts either shape.
      for (const line of ['HEALTH', 'GROUP', 'TRAVEL', 'VEHICLE', 'PROPERTY', 'FUNERAL']) {
        expect(providerModeForLine(line))
          .withContext(`${line} allows either a provider OR a member reimbursement`)
          .toBe('OPTIONAL');
      }
    });

    it('LIFE and DISABILITY are always paid to the member (FORBIDDEN)', () => {
      // The payout is structurally to the beneficiary — attaching a
      // provider would let the finance consumer route the money to the
      // wrong party. Kept strict.
      expect(providerModeForLine('LIFE')).toBe('FORBIDDEN');
      expect(providerModeForLine('DISABILITY')).toBe('FORBIDDEN');
    });

    it('unknown / null / undefined lines default to OPTIONAL', () => {
      // A misconfigured line silently rejecting a claim would be worse
      // than accepting a spurious provider that finance can flag later.
      expect(providerModeForLine(null)).toBe('OPTIONAL');
      expect(providerModeForLine(undefined)).toBe('OPTIONAL');
      expect(providerModeForLine('__nope__')).toBe('OPTIONAL');
    });

    it('every configured line has an explicit policy', () => {
      // The map should stay in sync with INSURANCE_LINES — a new line
      // added without a policy entry silently falls back to REQUIRED
      // and this catches that.
      for (const line of INSURANCE_LINES) {
        expect(PROVIDER_MODE_BY_LINE[line.value])
          .withContext(`line ${line.value} must have an explicit provider mode`)
          .toBeDefined();
      }
    });
  });
});
