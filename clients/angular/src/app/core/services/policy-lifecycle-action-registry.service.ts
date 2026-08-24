import { Injectable } from '@angular/core';

/**
 * Phase 13 §A per L4 + L5 (grill note 1). Client-side mirror of the
 * {@code PolicyReasonCode} enum registry in shared/lifecycle — one map
 * per policy source. Actions available depend only on the current
 * status (uniform 4-action shape); reason vocab is per-line.
 */
export type PolicySource =
  | 'LIFE_POLICY'
  | 'FUNERAL_POLICY'
  | 'DISABILITY_POLICY'
  | 'TRAVEL_POLICY'
  | 'VEHICLE_POLICY'
  | 'PROPERTY_POLICY';

export type PolicyAction = 'lapse' | 'terminate' | 'suspend' | 'reinstate';

export interface ReasonOption {
  code: string;
  label: string;
}

/** Endpoint path segment per source — matches the Phase 3 controller routes. */
const SOURCE_TO_PATH: Record<PolicySource, string> = {
  LIFE_POLICY: 'life-policies',
  FUNERAL_POLICY: 'funeral-policies',
  DISABILITY_POLICY: 'disability-policies',
  TRAVEL_POLICY: 'travel-policies',
  VEHICLE_POLICY: 'vehicle-policies',
  PROPERTY_POLICY: 'property-policies',
};

const ADMIN_CORRECTION: ReasonOption = { code: 'ADMIN_CORRECTION', label: 'Admin correction' };

const REASON_VOCAB: Record<PolicySource, ReasonOption[]> = {
  LIFE_POLICY: [
    { code: 'NON_PAYMENT', label: 'Non-payment' },
    { code: 'POLICYHOLDER_CANCEL', label: 'Policyholder cancellation' },
    { code: 'INSURED_EVENT', label: 'Insured event' },
    { code: 'MORTALITY', label: 'Mortality' },
    ADMIN_CORRECTION,
  ],
  FUNERAL_POLICY: [
    { code: 'NON_PAYMENT', label: 'Non-payment' },
    { code: 'POLICYHOLDER_CANCEL', label: 'Policyholder cancellation' },
    { code: 'INSURED_EVENT', label: 'Insured event' },
    ADMIN_CORRECTION,
  ],
  DISABILITY_POLICY: [
    { code: 'NON_PAYMENT', label: 'Non-payment' },
    { code: 'POLICYHOLDER_CANCEL', label: 'Policyholder cancellation' },
    { code: 'INSURED_EVENT', label: 'Insured event' },
    { code: 'RECOVERY', label: 'Recovery' },
    ADMIN_CORRECTION,
  ],
  TRAVEL_POLICY: [
    { code: 'NON_PAYMENT', label: 'Non-payment' },
    { code: 'TRIP_CANCELLED', label: 'Trip cancelled' },
    { code: 'INSURED_EVENT', label: 'Insured event' },
    ADMIN_CORRECTION,
  ],
  VEHICLE_POLICY: [
    { code: 'NON_PAYMENT', label: 'Non-payment' },
    { code: 'SOLD', label: 'Vehicle sold' },
    { code: 'TOTAL_LOSS', label: 'Total loss' },
    { code: 'STORAGE_SUSPEND', label: 'Storage suspend' },
    { code: 'POLICYHOLDER_CANCEL', label: 'Policyholder cancellation' },
    ADMIN_CORRECTION,
  ],
  PROPERTY_POLICY: [
    { code: 'NON_PAYMENT', label: 'Non-payment' },
    { code: 'SOLD', label: 'Property sold' },
    { code: 'TOTAL_LOSS', label: 'Total loss' },
    { code: 'POLICYHOLDER_CANCEL', label: 'Policyholder cancellation' },
    ADMIN_CORRECTION,
  ],
};

const ACTION_LABELS: Record<PolicyAction, string> = {
  lapse: 'Lapse policy',
  terminate: 'Terminate policy',
  suspend: 'Suspend policy',
  reinstate: 'Reinstate policy',
};

/**
 * Terminal status has zero available actions — the policy is done.
 * Active can go to any of the three non-active states. Suspended and
 * lapsed can be reinstated + terminated (lapsed→suspended is disallowed
 * intentionally, matching the backend state machine).
 */
const ACTIONS_BY_STATUS: Record<string, PolicyAction[]> = {
  active: ['lapse', 'terminate', 'suspend'],
  suspended: ['reinstate', 'lapse', 'terminate'],
  lapsed: ['reinstate', 'terminate'],
  terminated: [],
  draft: [],
};

@Injectable({ providedIn: 'root' })
export class PolicyLifecycleActionRegistryService {
  actionsFor(currentStatus: string): PolicyAction[] {
    return ACTIONS_BY_STATUS[currentStatus?.toLowerCase()] ?? [];
  }

  reasonsFor(source: PolicySource): ReasonOption[] {
    return REASON_VOCAB[source] ?? [];
  }

  actionLabel(action: PolicyAction): string {
    return ACTION_LABELS[action] ?? action;
  }

  /** Bootstrap the endpoint path — kept in one place so form components don't build URLs. */
  endpointFor(source: PolicySource, id: string, action: PolicyAction): string {
    return `/${SOURCE_TO_PATH[source]}/${id}/${action}`;
  }
}
