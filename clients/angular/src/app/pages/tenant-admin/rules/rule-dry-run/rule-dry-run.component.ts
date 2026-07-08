import { Component, EventEmitter, Input, OnInit, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import {
  DryRunRequest,
  DryRunResponse,
  RuleCategory,
  RulesService,
  TenantRule,
} from '../../../../core/services/rules.service';

interface FactSeed {
  category: RuleCategory;
  claim?: Record<string, unknown>;
  member?: Record<string, unknown>;
  lifecycle?: Record<string, unknown>;
  contribution?: Record<string, unknown>;
  paymentRun?: Record<string, unknown>;
  provider?: Record<string, unknown>;
  time?: Record<string, unknown>;
}

/**
 * Per-category seed facts so users get a sensible starting point in the
 * dry-run editor. Each value is an obvious example; the user is expected
 * to tweak fields they care about before running.
 *
 * Adding a new seed: a new entry below — the seed picker keys on
 * {@code category} so it lights up automatically.
 */
const FACT_SEEDS: FactSeed[] = [
  // ── Claims pipeline ────────────────────────────────────────────────────
  {
    category: 'ELIGIBILITY',
    claim: { claimId: 'CLM-DEMO-1', daysSinceService: 95, isEmergency: false, isAccident: false },
    member: { memberId: 'MEM-DEMO', status: 'ACTIVE', arrearsMonths: 0 },
  },
  {
    category: 'WAITING_PERIOD',
    claim: { benefitCategory: 'MATERNITY', isEmergency: false, isAccident: false },
    member: { memberId: 'MEM-DEMO', daysSinceEnrollment: 200, gender: 'FEMALE' },
  },
  {
    category: 'BENEFIT_LIMIT',
    claim: { benefitCategory: 'GENERAL', amount: 1500, currencyCode: 'USD' },
    member: { memberId: 'MEM-DEMO', benefitRemaining: 0 },
  },
  {
    category: 'BENEFIT_PRORATION',
    claim: { benefitCategory: 'DENTAL', schemeId: 'SCH-DEMO', amount: 500, currencyCode: 'USD' },
    member: { memberId: 'MEM-DEMO', daysSinceSchemeChange: 30, benefitLimit: 10000 },
  },
  {
    category: 'CO_PAYMENT',
    claim: { benefitCategory: 'OPTICAL', amount: 800, currencyCode: 'USD' },
    provider: { providerId: 'PRV-DEMO', inNetwork: false },
  },
  {
    category: 'PRE_AUTHORIZATION',
    claim: { isElective: true, hasPreAuth: false, preAuthStatus: null },
  },
  {
    category: 'TARIFF_PRICING',
    claim: { tariffCode: 'GP01', amount: 1200, currencyCode: 'USD' },
  },
  {
    category: 'CLINICAL_VALIDATION',
    claim: { benefitCategory: 'MATERNITY' },
    member: { gender: 'MALE', age: 30 },
  },

  // ── Member lifecycle ───────────────────────────────────────────────────
  {
    category: 'AGE_GROUP',
    lifecycle: { memberId: 'MEM-DEMO', age: 42, gender: 'FEMALE' },
  },
  {
    category: 'MEMBER_LIFECYCLE',
    lifecycle: { memberId: 'MEM-DEMO', currentStatus: 'ACTIVE', contributionsInArrearsMonths: 6 },
  },
  {
    category: 'UNDERWRITING',
    lifecycle: {
      memberId: 'MEM-DEMO', age: 55, smoker: true, bmi: 32,
      hasPreExistingConditions: true,
    },
  },

  // ── Contributions ──────────────────────────────────────────────────────
  {
    category: 'CONTRIBUTION_PRICING',
    contribution: {
      memberId: 'MEM-DEMO', schemeId: 'STANDARD',
      memberAge: 35, dependantCount: 2, currencyCode: 'USD',
    },
  },
  {
    category: 'CONTRIBUTION_BILLING',
    contribution: {
      memberId: 'MEM-DEMO', daysOverdue: 45, paid: false, currencyCode: 'USD',
    },
  },

  // ── Finance ────────────────────────────────────────────────────────────
  {
    category: 'PROVIDER_PAYMENT',
    paymentRun: {
      paymentRunId: 'PR-DEMO', providerId: 'PRV-DEMO',
      providerVerified: true, outstandingClaimsCount: 12,
      amountDue: 5000, currencyCode: 'USD',
    },
    time: { dayOfMonth: 15, monthOfYear: 5, year: 2026 },
  },
  {
    category: 'RECONCILIATION',
    paymentRun: {
      paymentRunId: 'PR-DEMO', providerId: 'PRV-DEMO',
      providerVerified: true, amountDue: 5000,
    },
  },
];

/**
 * Side-panel sandbox: edit fact JSON, click Run, see decision + matched
 * results. The seed picker pre-fills the editor with a typical fact set
 * for the rule's category so authors don't start from a blank page.
 */
@Component({
  selector: 'app-rule-dry-run',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent],
  templateUrl: './rule-dry-run.component.html',
  styleUrl: './rule-dry-run.component.scss',
})
export class RuleDryRunComponent implements OnInit {
  @Input({ required: true }) rule!: TenantRule;
  @Output() close = new EventEmitter<void>();

  factsJson = '';
  parseError = '';
  serverError = '';
  running = false;
  result?: DryRunResponse;

  constructor(private rulesService: RulesService) {}

  ngOnInit(): void {
    this.loadSeed();
  }

  loadSeed(): void {
    const seed = FACT_SEEDS.find(s => s.category === this.rule.category) ?? FACT_SEEDS[0];
    // Strip null fact slots so the editor JSON only carries facts that are
    // actually relevant to the category. Keeps the visible payload tight.
    const payload: Record<string, unknown> = {};
    if (seed.claim)        payload['claim']        = seed.claim;
    if (seed.member)       payload['member']       = seed.member;
    if (seed.lifecycle)    payload['lifecycle']    = seed.lifecycle;
    if (seed.contribution) payload['contribution'] = seed.contribution;
    if (seed.paymentRun)   payload['paymentRun']   = seed.paymentRun;
    if (seed.provider)     payload['provider']     = seed.provider;
    if (seed.time)         payload['time']         = seed.time;
    // The backend always inserts a ClaimFact even if the rule doesn't use it,
    // so seed an empty object when no claim is supplied.
    if (!payload['claim']) payload['claim'] = {};
    this.factsJson = JSON.stringify(payload, null, 2);
    this.result = undefined;
    this.parseError = '';
    this.serverError = '';
  }

  run(): void {
    this.parseError = '';
    this.serverError = '';
    this.result = undefined;

    let parsed: Record<string, unknown>;
    try {
      parsed = JSON.parse(this.factsJson);
    } catch (e: unknown) {
      this.parseError = `Invalid JSON: ${(e as Error).message}`;
      return;
    }

    const body: DryRunRequest = {
      claim:        (parsed['claim']        as Record<string, unknown>) ?? {},
      member:       (parsed['member']       as Record<string, unknown>) ?? undefined,
      dependant:    (parsed['dependant']    as Record<string, unknown>) ?? undefined,
      provider:     (parsed['provider']     as Record<string, unknown>) ?? undefined,
      family:       (parsed['family']       as Record<string, unknown>) ?? undefined,
      lifecycle:    (parsed['lifecycle']    as Record<string, unknown>) ?? undefined,
      contribution: (parsed['contribution'] as Record<string, unknown>) ?? undefined,
      paymentRun:   (parsed['paymentRun']   as Record<string, unknown>) ?? undefined,
      time:         (parsed['time']         as Record<string, unknown>) ?? undefined,
    };

    this.running = true;
    this.rulesService.dryRun(this.rule.id, body).subscribe({
      next: (res) => { this.result = res; this.running = false; },
      error: (err) => {
        this.serverError = err?.error?.detail ?? 'Dry-run failed.';
        this.running = false;
      },
    });
  }

  decisionClass(): string {
    if (!this.result) return '';
    return this.result.decision === 'REJECTED' ? 'reject' : 'accept';
  }

  onClose(): void { this.close.emit(); }
}
