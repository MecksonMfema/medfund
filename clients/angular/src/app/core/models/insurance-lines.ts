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
