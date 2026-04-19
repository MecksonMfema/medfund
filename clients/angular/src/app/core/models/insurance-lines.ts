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
