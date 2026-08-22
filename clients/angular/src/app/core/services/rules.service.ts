import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';

/**
 * Per-tenant Drools rule.
 * The {@code definition} field is the parsed JSON tree returned by the
 * backend (RuleDefinition shape: conditions + action).
 */
export interface TenantRule {
  id: string;
  tenantId: string;
  ruleKey: string;
  name: string;
  description?: string;
  category: RuleCategory;
  templateId?: string;
  priority: number;
  definition: RuleDefinition;
  drlOverride?: string;
  enabled: boolean;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface RuleTemplate {
  id: string;
  name: string;
  description?: string;
  category: RuleCategory;
  priority: number;
  definition: RuleDefinition;
}

export type RuleCategory =
  // Member lifecycle
  | 'MEMBER_LIFECYCLE'
  | 'AGE_GROUP'
  | 'UNDERWRITING'
  // Contributions
  | 'CONTRIBUTION_PRICING'
  | 'CONTRIBUTION_BILLING'
  // Claims pipeline
  | 'ELIGIBILITY'
  | 'WAITING_PERIOD'
  | 'BENEFIT_LIMIT'
  | 'BENEFIT_PRORATION'
  | 'CO_PAYMENT'
  | 'PRE_AUTHORIZATION'
  | 'TARIFF_PRICING'
  | 'CLINICAL_VALIDATION'
  // Finance
  | 'PROVIDER_PAYMENT'
  | 'RECONCILIATION'
  // Reinsurance
  | 'REINSURANCE';

/**
 * Categories grouped by lifecycle stage. The Rules Engine page renders this
 * list verbatim — adding a new category to {@link RuleCategory} above and a
 * corresponding entry here is all the frontend needs to surface it.
 */
export const RULE_CATEGORIES: { id: RuleCategory; label: string; icon: string }[] = [
  // Member lifecycle
  { id: 'MEMBER_LIFECYCLE',     label: 'Member Lifecycle',     icon: 'users' },
  { id: 'AGE_GROUP',            label: 'Age Group',            icon: 'user-check' },
  { id: 'UNDERWRITING',         label: 'Underwriting',         icon: 'shield' },
  // Contributions
  { id: 'CONTRIBUTION_PRICING', label: 'Contribution Pricing', icon: 'wallet' },
  { id: 'CONTRIBUTION_BILLING', label: 'Contribution Billing', icon: 'calendar' },
  // Claims pipeline
  { id: 'ELIGIBILITY',          label: 'Eligibility',          icon: 'check-circle' },
  { id: 'WAITING_PERIOD',       label: 'Waiting Period',       icon: 'clock' },
  { id: 'BENEFIT_LIMIT',        label: 'Benefit Limit',        icon: 'briefcase' },
  { id: 'BENEFIT_PRORATION',    label: 'Benefit Proration',    icon: 'divide' },
  { id: 'CO_PAYMENT',           label: 'Co-Payment',           icon: 'dollar' },
  { id: 'PRE_AUTHORIZATION',    label: 'Pre-Authorisation',    icon: 'file-text' },
  { id: 'TARIFF_PRICING',       label: 'Tariff Pricing',       icon: 'trending-up' },
  { id: 'CLINICAL_VALIDATION',  label: 'Clinical Validation',  icon: 'file-medical' },
  // Finance
  { id: 'PROVIDER_PAYMENT',     label: 'Provider Payment',     icon: 'banknote' },
  { id: 'RECONCILIATION',       label: 'Reconciliation',       icon: 'activity' },
  // Reinsurance
  { id: 'REINSURANCE',          label: 'Reinsurance',          icon: 'shield-plus' },
];

export interface RuleDefinition {
  id?: string;
  name?: string;
  description?: string;
  category?: string;
  priority?: number;
  enabled?: boolean;
  status?: string;
  version?: number;
  conditions?: ConditionGroup;
  action?: RuleAction;
}

export interface ConditionGroup {
  operator: 'AND' | 'OR';
  items: Condition[];
}

export interface Condition {
  field: string;
  operator: string; // EQUALS, NOT_EQUALS, GREATER_THAN, LESS_THAN, etc.
  value: unknown;
}

export interface RuleAction {
  type: string; // REJECT, CAP_TO_TARIFF, FLAG, WARN
  rejectionCode?: string;
  message: string;
}

export interface DryRunRequest {
  claim: Record<string, unknown>;
  member?: Record<string, unknown>;
  dependant?: Record<string, unknown>;
  provider?: Record<string, unknown>;
  family?: Record<string, unknown>;
  /** Lifecycle/onboarding fact — for AGE_GROUP, MEMBER_LIFECYCLE, UNDERWRITING rules. */
  lifecycle?: Record<string, unknown>;
  /** Premium / billing fact — for CONTRIBUTION_PRICING, CONTRIBUTION_BILLING rules. */
  contribution?: Record<string, unknown>;
  /** Provider payment-run fact — for PROVIDER_PAYMENT, RECONCILIATION rules. */
  paymentRun?: Record<string, unknown>;
  /** "Today" fact — drives date-aware rules without reading the system clock. */
  time?: Record<string, unknown>;
}

export interface DryRunResponse {
  ruleId: string;
  decision: 'ACCEPTED' | 'REJECTED' | string;
  matched: { type: string; code?: string; message: string }[];
  executionMs: number;
}

export interface CreateRulePayload {
  ruleKey: string;
  name: string;
  description?: string;
  category: RuleCategory;
  templateId?: string;
  priority?: number;
  definition: RuleDefinition;
  drlOverride?: string;
  enabled?: boolean;
}

export type UpdateRulePayload = Partial<Omit<CreateRulePayload, 'ruleKey'>>;

@Injectable({ providedIn: 'root' })
export class RulesService {
  constructor(private api: ApiService) {}

  list(category?: RuleCategory): Observable<TenantRule[]> {
    const params: Record<string, string> = {};
    if (category) params['category'] = category;
    return this.api.get<TenantRule[]>('/rules', params);
  }

  get(id: string): Observable<TenantRule> {
    return this.api.get<TenantRule>(`/rules/${id}`);
  }

  create(body: CreateRulePayload): Observable<TenantRule> {
    return this.api.post<TenantRule>('/rules', body);
  }

  update(id: string, body: UpdateRulePayload): Observable<TenantRule> {
    return this.api.put<TenantRule>(`/rules/${id}`, body);
  }

  delete(id: string): Observable<void> {
    return this.api.delete<void>(`/rules/${id}`);
  }

  enable(id: string): Observable<TenantRule> {
    return this.api.post<TenantRule>(`/rules/${id}/enable`, {});
  }

  disable(id: string): Observable<TenantRule> {
    return this.api.post<TenantRule>(`/rules/${id}/disable`, {});
  }

  dryRun(id: string, body: DryRunRequest): Observable<DryRunResponse> {
    return this.api.post<DryRunResponse>(`/rules/${id}/dry-run`, body);
  }

  getTemplates(): Observable<RuleTemplate[]> {
    return this.api.get<RuleTemplate[]>('/rule-templates');
  }
}
