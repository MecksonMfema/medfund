export interface InsuranceLine {
  value: string;
  label: string;
  description: string;
  /**
   * Provider types that are relevant for this insurance line.
   * Used to pre-filter the platform-wide provider network when a tenant portal
   * renders a provider directory or claim submission form.
   */
  providerTypes: string[];
  /**
   * Default label for the provider registration/licence number field when
   * this line is the tenant's primary insurance type.
   * Stored in tenant settings as providerRegLabel and shown in all
   * provider-related UI within that tenant's portal.
   */
  registrationLabel: string;
}

export const INSURANCE_LINES: InsuranceLine[] = [
  {
    value: 'HEALTH',
    label: 'Health Insurance',
    description: 'Medical, hospital and primary care coverage',
    providerTypes: ['HEALTHCARE'],
    registrationLabel: 'AHFOZ / Practice Number',
  },
  {
    value: 'LIFE',
    label: 'Life Insurance',
    description: 'Term life, whole life and endowment policies',
    providerTypes: ['FINANCIAL'],
    registrationLabel: 'FSP / Financial Services Reg. Number',
  },
  {
    value: 'VEHICLE',
    label: 'Vehicle / Motor',
    description: 'Comprehensive, third-party and fleet cover',
    providerTypes: ['AUTOMOTIVE'],
    registrationLabel: 'Workshop Licence Number',
  },
  {
    value: 'FUNERAL',
    label: 'Funeral Insurance',
    description: 'Funeral and burial benefit plans',
    providerTypes: ['FUNERAL'],
    registrationLabel: 'Funeral Services Reg. Number',
  },
  {
    value: 'PROPERTY',
    label: 'Property / Home',
    description: 'Buildings, contents and all-risk cover',
    providerTypes: ['PROPERTY'],
    registrationLabel: 'Property Services Reg. Number',
  },
  {
    value: 'GROUP',
    label: 'Group / Employee Benefits',
    description: 'Employer-sponsored schemes and group policies',
    providerTypes: ['HEALTHCARE', 'FINANCIAL'],
    registrationLabel: 'AHFOZ / Practice Number',
  },
  {
    value: 'TRAVEL',
    label: 'Travel Insurance',
    description: 'Emergency medical, trip cancellation and baggage',
    providerTypes: ['HEALTHCARE'],
    registrationLabel: 'Practice Number',
  },
  {
    value: 'DISABILITY',
    label: 'Disability Insurance',
    description: 'Income protection and disability benefit cover',
    providerTypes: ['HEALTHCARE', 'FINANCIAL'],
    registrationLabel: 'Practice Number',
  },
];

/** Parse insurance lines from a raw tenant settings JSON string. */
export function parseInsuranceLines(settings: string | null | undefined): string[] {
  if (!settings || settings === '{}') return [];
  try {
    const parsed = JSON.parse(settings);
    const lines = parsed['insuranceLines'];
    return Array.isArray(lines) ? lines : [];
  } catch {
    return [];
  }
}

/** Parse the custom provider registration label from tenant settings, if set. */
export function parseProviderRegLabel(settings: string | null | undefined): string {
  if (!settings || settings === '{}') return '';
  try {
    return JSON.parse(settings)?.providerRegLabel ?? '';
  } catch {
    return '';
  }
}

/** Whether drug-claim workflows are enabled for this tenant. Defaults to true. */
export function parseDrugClaimsEnabled(settings: string | null | undefined): boolean {
  if (!settings || settings === '{}') return true;
  try {
    const v = JSON.parse(settings)?.drugClaimsEnabled;
    return v === undefined ? true : !!v;
  } catch {
    return true;
  }
}

/**
 * Tenant-configurable scheme terminology. Some tenants call them "Schemes",
 * others "Packages", "Plans", or "Policies". Returns the singular + plural
 * labels for use across the operational portal — sidebar, page titles, etc.
 */
export interface SchemeTerminology {
  singular: string;
  plural: string;
}

export const DEFAULT_SCHEME_TERMINOLOGY: SchemeTerminology = { singular: 'Scheme', plural: 'Schemes' };

/** Predefined choices the Settings UI offers in addition to a custom override. */
export const SCHEME_TERMINOLOGY_PRESETS: Array<SchemeTerminology & { id: string }> = [
  { id: 'scheme',  singular: 'Scheme',  plural: 'Schemes'  },
  { id: 'package', singular: 'Package', plural: 'Packages' },
  { id: 'plan',    singular: 'Plan',    plural: 'Plans'    },
  { id: 'policy',  singular: 'Policy',  plural: 'Policies' },
  { id: 'product', singular: 'Product', plural: 'Products' },
];

export function parseSchemeTerminology(settings: string | null | undefined): SchemeTerminology {
  if (!settings || settings === '{}') return { ...DEFAULT_SCHEME_TERMINOLOGY };
  try {
    const parsed = JSON.parse(settings);
    return {
      singular: parsed?.schemeLabelSingular || DEFAULT_SCHEME_TERMINOLOGY.singular,
      plural:   parsed?.schemeLabelPlural   || DEFAULT_SCHEME_TERMINOLOGY.plural,
    };
  } catch {
    return { ...DEFAULT_SCHEME_TERMINOLOGY };
  }
}

/**
 * Derives the default provider registration number label from a tenant's
 * insurance lines. Uses the first matching line's label as the default.
 * Falls back to the generic label when no lines match.
 */
export function deriveProviderRegLabel(lines: string[]): string {
  for (const value of lines) {
    const line = INSURANCE_LINES.find(l => l.value === value);
    if (line) return line.registrationLabel;
  }
  return 'Registration / AHFOZ / Licence Number';
}

/** Human-readable label for a single insurance line value. */
export function insuranceLineLabel(value: string): string {
  return INSURANCE_LINES.find(l => l.value === value)?.label ?? value;
}

/**
 * A choice in the scheme-form's "Scheme type" dropdown — labelled and
 * categorised by the insurance line it belongs to so a tenant only sees
 * the types that are relevant to the products they sell.
 */
export interface SchemeTypeOption {
  code: string;
  label: string;
  /** Used as the section / optgroup header in the dropdown. */
  line: string;
}

/**
 * Catalogue of scheme types keyed by insurance line. Tenants that have
 * configured {@code insuranceLines = ['HEALTH']} see only the HEALTH
 * entries; a multi-line tenant (e.g. {@code ['HEALTH','LIFE']}) sees the
 * union, in the order their lines are configured.
 */
export const SCHEME_TYPES_BY_LINE: Record<string, ReadonlyArray<Omit<SchemeTypeOption, 'line'>>> = {
  HEALTH: [
    { code: 'medical_aid',      label: 'Medical aid'         },
    { code: 'health_insurance', label: 'Health insurance'    },
    { code: 'hmo',              label: 'HMO'                 },
    { code: 'wellness',         label: 'Wellness'            },
    { code: 'hospital_cash',    label: 'Hospital cash plan'  },
  ],
  LIFE: [
    { code: 'term_life',        label: 'Term life'           },
    { code: 'whole_life',       label: 'Whole life'          },
    { code: 'endowment',        label: 'Endowment'           },
  ],
  VEHICLE: [
    { code: 'comprehensive',    label: 'Comprehensive'       },
    { code: 'third_party',      label: 'Third-party'         },
    { code: 'fleet',            label: 'Fleet'               },
  ],
  FUNERAL: [
    { code: 'funeral_benefit',  label: 'Funeral benefit'     },
    { code: 'family_funeral',   label: 'Family funeral plan' },
  ],
  PROPERTY: [
    { code: 'buildings',        label: 'Buildings cover'     },
    { code: 'contents',         label: 'Contents cover'      },
    { code: 'all_risk',         label: 'All-risk'            },
  ],
  GROUP: [
    { code: 'group_health',     label: 'Group health'        },
    { code: 'group_life',       label: 'Group life'          },
    { code: 'group_disability', label: 'Group disability'    },
  ],
  TRAVEL: [
    { code: 'single_trip',      label: 'Single-trip'         },
    { code: 'multi_trip',       label: 'Multi-trip'          },
    { code: 'annual_travel',    label: 'Annual travel'       },
  ],
  DISABILITY: [
    { code: 'short_term_disability', label: 'Short-term disability' },
    { code: 'long_term_disability',  label: 'Long-term disability'  },
    { code: 'income_protection',     label: 'Income protection'     },
  ],
};

/**
 * Returns the scheme types available to a tenant given its configured
 * insurance lines, in the order the lines were configured. Falls back to
 * the HEALTH catalogue when the tenant has no lines set yet — that's
 * historically what new tenants defaulted to and keeps the form usable
 * during the brief window before the operator configures lines.
 */
export function schemeTypesForLines(lines: string[]): SchemeTypeOption[] {
  const source = lines.length > 0 ? lines : ['HEALTH'];
  const out: SchemeTypeOption[] = [];
  for (const line of source) {
    const bucket = SCHEME_TYPES_BY_LINE[line];
    if (!bucket) continue;
    for (const t of bucket) out.push({ code: t.code, label: t.label, line });
  }
  return out;
}

/**
 * Lines that are person-centric — i.e. priced and covered around an
 * individual member. These support age-banded contributions and benefit
 * caps (annual/event limits). Asset-centric lines (VEHICLE, PROPERTY) are
 * priced from the insured asset's value, not the policyholder's age, so
 * the Age Groups and Scheme Benefits screens are hidden for them.
 */
export const PERSON_CENTRIC_LINES: ReadonlySet<string> = new Set([
  'HEALTH', 'LIFE', 'FUNERAL', 'GROUP', 'TRAVEL', 'DISABILITY',
]);

export function isPersonCentricLine(line: string | null | undefined): boolean {
  return !!line && PERSON_CENTRIC_LINES.has(line);
}

/**
 * Reverse lookup: scheme_type code → insurance line. Built at module load
 * by flattening SCHEME_TYPES_BY_LINE so the form can derive a scheme's
 * line from the dropdown selection without an extra UI control. The same
 * mapping is mirrored in the V021 Flyway migration; keep the two in sync
 * when adding new scheme types.
 */
export const LINE_FOR_SCHEME_TYPE: Readonly<Record<string, string>> = (() => {
  const map: Record<string, string> = {};
  for (const [line, bucket] of Object.entries(SCHEME_TYPES_BY_LINE)) {
    for (const t of bucket) map[t.code] = line;
  }
  return map;
})();

/**
 * Returns the insurance line for a given scheme type code, falling back
 * to HEALTH for unknown / blank codes — matches the V021 migration's
 * backfill default and the existing `medical_aid` column default.
 */
export function lineForSchemeType(schemeType: string | null | undefined): string {
  if (!schemeType) return 'HEALTH';
  return LINE_FOR_SCHEME_TYPE[schemeType] ?? 'HEALTH';
}
