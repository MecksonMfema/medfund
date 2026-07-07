import { of, throwError } from 'rxjs';
import { MemberFormComponent } from './member-form.component';
import { Member, MembersService } from '../../../../core/services/members.service';
import { TenantService } from '../../../../core/services/tenant.service';
import { ToastService } from '../../../../shared/components/toast/toast.service';

function makeMember(): Member {
  return {
    id: 'm-new', memberNumber: 'M-1234',
    firstName: 'New', lastName: 'Member', dateOfBirth: '1990-01-01',
    email: '', phone: '', status: 'enrolled',
    groupId: null, schemeId: null,
    enrollmentDate: '2026-01-01', createdAt: '2026-01-01',
  };
}

/** Build a form snapshot with every required field populated so the
 *  spec can assert one behaviour at a time without re-listing them. */
function withValidRequiredFields(comp: MemberFormComponent, overrides: Partial<any> = {}): void {
  comp.form = {
    ...comp.form,
    firstName:   'Sarah',
    lastName:    'Doe',
    dateOfBirth: '1990-01-01',
    gender:      'female',
    nationalId:  '63-1234567',
    email:       'sarah@example.com',
    schemeId:    'sch-1',
    ...overrides,
  };
}

class StubMembers {
  enrollCalls: any[] = [];
  shouldFail = false;
  enroll = (data: any) => {
    this.enrollCalls.push(data);
    return this.shouldFail
      ? throwError(() => ({ error: { detail: 'duplicate id' } }))
      : of({ ...makeMember(), ...data });
  };
}
class StubToast {
  successes: string[] = []; errors: string[] = [];
  success = (m: string) => this.successes.push(m);
  error   = (m: string) => this.errors.push(m);
}
class StubRouter { navigated: any[] = []; navigate = (cmds: any[]) => this.navigated.push(cmds); }

/**
 * Minimal TenantService stand-in. Toggles between STANDARD and
 * INDIVIDUAL pricing so the custom-premium section can be tested in
 * both directions from the same helper.
 */
class StubTenant {
  private model: string | null = 'STANDARD';
  setPricingModel(m: string | null): this { this.model = m; return this; }
  getTenant = () => this.model == null ? null : ({ pricingModel: this.model } as any);
}

// Configurable pricing-suggestion stub — drives the AI suggest tests.
class StubPricingSuggestion {
  suggestCalls: any[] = [];
  response: any = {
    suggestedAmount: '175.50', currencyCode: 'USD',
    rationale: 'Female × Smoker × Adult', factors: ['Adult 30-40'], stub: true,
  };
  fail = false;
  suggest = (req: any) => {
    this.suggestCalls.push(req);
    return this.fail
      ? throwError(() => ({ error: { detail: 'model down' } }))
      : of<any>(this.response);
  };
}

function instantiate(pricingModel: string | null = 'STANDARD') {
  const members = new StubMembers();
  const router = new StubRouter();
  const toast = new StubToast();
  const tenant = new StubTenant().setPricingModel(pricingModel);
  const pricing = new StubPricingSuggestion();
  // V050: MemberFormComponent gained a ContributionsService dep to load the
  // picked scheme's age-range for the warning banner. Stub with a getSchemeById
  // that yields Mono.empty()-ish; specs never exercise the scheme lookup.
  const contributions = {
    getSchemeById: () => ({ subscribe: (obs: any) => { obs?.error?.(new Error('stub')); } }),
  };
  const comp = new MemberFormComponent(
    members as unknown as MembersService,
    router as any,
    toast as unknown as ToastService,
    tenant as unknown as TenantService,
    pricing as any,
    contributions as any,
  );
  return { comp, members, router, toast, tenant, pricing };
}

describe('MemberFormComponent', () => {
  it('blocks submit when required fields are missing', () => {
    const { comp, members } = instantiate();
    comp.form.firstName = 'A';
    // missing: last name, DOB, gender, national ID, email, scheme
    comp.submit();
    expect(members.enrollCalls.length).toBe(0);
    expect(comp.errorMessage).toContain('missing');
  });

  it('blocks submit when scheme is the only field missing', () => {
    const { comp, members } = instantiate();
    withValidRequiredFields(comp, { schemeId: '' });
    comp.submit();
    expect(members.enrollCalls.length).toBe(0);
    expect(comp.errorMessage).toContain('scheme');
  });

  it('enrols + navigates to the new member detail on success', () => {
    const { comp, members, router, toast } = instantiate();
    withValidRequiredFields(comp);
    comp.submit();
    expect(members.enrollCalls[0].email).toBe('sarah@example.com');
    expect(router.navigated[0]).toEqual(['/tenant/members', 'm-new']);
    expect(toast.successes[0]).toContain('Sarah Doe');
  });

  it('forces the enrolment date to the 1st of the chosen month', () => {
    const { comp, members } = instantiate();
    withValidRequiredFields(comp, { enrollmentDate: '2026-03-17' });
    comp.submit();
    expect(members.enrollCalls[0].enrollmentDate).toBe('2026-03-01');
  });

  it('snaps the enrollmentDate model on (ngModelChange)', () => {
    const { comp } = instantiate();
    comp.form.enrollmentDate = '2026-07-23';
    comp.onEnrollmentDateChange();
    expect(comp.form.enrollmentDate).toBe('2026-07-01');
  });

  it('surfaces enrolment errors on the banner and via toast', () => {
    const { comp, members, toast } = instantiate();
    members.shouldFail = true;
    withValidRequiredFields(comp);
    comp.submit();
    expect(comp.errorMessage).toBe('duplicate id');
    expect(toast.errors[0]).toBe('duplicate id');
  });

  // ------------------------------------------------------------------
  // Custom-premium at enrolment — INDIVIDUAL pricing model only.
  //
  // Frontend gate is defense-in-depth: the backend gates too, but a
  // STANDARD-model tenant should never see the section. Absent the
  // gate here, a stale draft with an override amount would still hit
  // the wire — the tests below pin both directions.
  // ------------------------------------------------------------------

  describe('custom-premium at enrolment', () => {
    it('is hidden and omitted from the payload for STANDARD-model tenants', () => {
      const { comp, members } = instantiate('STANDARD');
      expect(comp.individualPricing).toBeFalse();

      withValidRequiredFields(comp, {
        // Stale draft in-memory — even if the operator somehow set it,
        // the payload must not carry it for a STANDARD tenant.
        billingOverrideAmount: 125.50,
        billingOverrideEffectiveFrom: '2026-08-01',
        billingOverrideReason: 'corporate rate',
      });
      comp.submit();

      const sent = members.enrollCalls[0];
      expect('billingOverrideAmount' in sent).toBeFalse();
      expect('billingOverrideReason' in sent).toBeFalse();
      expect('billingOverrideEffectiveFrom' in sent).toBeFalse();
    });

    it('is exposed to INDIVIDUAL-model tenants and travels on the payload', () => {
      const { comp, members } = instantiate('INDIVIDUAL');
      expect(comp.individualPricing).toBeTrue();

      withValidRequiredFields(comp, {
        billingOverrideAmount: 125.50,
        billingOverrideEffectiveFrom: '2026-08-01',
        billingOverrideReason: 'corporate rate',
      });
      comp.submit();

      const sent = members.enrollCalls[0];
      expect(sent.billingOverrideAmount).toBe(125.50);
      expect(sent.billingOverrideReason).toBe('corporate rate');
      expect(sent.billingOverrideEffectiveFrom).toBe('2026-08-01');
    });

    it('rejects a half-filled triple (amount without effective_from)', () => {
      // Mirrors the backend guard: amount != null → effective_from
      // required. Regression here would allow a 400 round-trip.
      const { comp, members, toast } = instantiate('INDIVIDUAL');

      withValidRequiredFields(comp, {
        billingOverrideAmount: 125.50,
        billingOverrideEffectiveFrom: '',
        billingOverrideReason: 'corporate rate',
      });
      comp.submit();

      expect(members.enrollCalls.length).toBe(0);
      expect(comp.errorMessage).toContain('effective_from');
      expect(toast.errors[0]).toContain('effective_from');
    });

    it('omits the triple when amount is null even on INDIVIDUAL', () => {
      // Optional override — an INDIVIDUAL tenant can still enrol
      // without setting a custom price.
      const { comp, members } = instantiate('INDIVIDUAL');
      withValidRequiredFields(comp);
      comp.submit();

      const sent = members.enrollCalls[0];
      expect('billingOverrideAmount' in sent).toBeFalse();
    });

    it('trims the reason before sending', () => {
      const { comp, members } = instantiate('INDIVIDUAL');
      withValidRequiredFields(comp, {
        billingOverrideAmount: 100,
        billingOverrideEffectiveFrom: '2026-08-01',
        billingOverrideReason: '   student rate   ',
      });
      comp.submit();
      expect(members.enrollCalls[0].billingOverrideReason).toBe('student rate');
    });

    it('sends undefined reason when it is blank', () => {
      const { comp, members } = instantiate('INDIVIDUAL');
      withValidRequiredFields(comp, {
        billingOverrideAmount: 100,
        billingOverrideEffectiveFrom: '2026-08-01',
        billingOverrideReason: '',
      });
      comp.submit();
      expect(members.enrollCalls[0].billingOverrideReason).toBeUndefined();
    });
  });

  // ------------------------------------------------------------------
  // Pricing model gates — individualPricing + aiSuggestionsEnabled.
  //
  // These getters drive whether the Custom-premium section and the
  // "Suggest with AI" button render. They are the single source of
  // truth for the frontend gate. Keep them locked so a rename in
  // TenantService doesn't silently turn overrides off for every tenant.
  // ------------------------------------------------------------------

  describe('pricing model gates', () => {
    it('treats AI_DRIVEN as hybrid — individualPricing is true', () => {
      const { comp } = instantiate('AI_DRIVEN');
      expect(comp.individualPricing).toBeTrue();
    });

    it('gates aiSuggestionsEnabled ONLY on AI_DRIVEN', () => {
      // Explicit table so a future 4th mode doesn't accidentally
      // enable the AI helper by default.
      expect(instantiate('AI_DRIVEN').comp.aiSuggestionsEnabled).toBeTrue();
      expect(instantiate('INDIVIDUAL').comp.aiSuggestionsEnabled).toBeFalse();
      expect(instantiate('STANDARD').comp.aiSuggestionsEnabled).toBeFalse();
    });

    it('surfaces the tenant pricing model via getter; defaults to STANDARD when tenant is null', () => {
      // The hint banner reads .tenantPricingModel — null fallback
      // matters because getTenant() can return null during a
      // tenant-context refresh.
      expect(instantiate('STANDARD').comp.tenantPricingModel).toBe('STANDARD');
      expect(instantiate('AI_DRIVEN').comp.tenantPricingModel).toBe('AI_DRIVEN');
      expect(instantiate(null).comp.tenantPricingModel).toBe('STANDARD');
    });
  });

  // ------------------------------------------------------------------
  // Regression guard: the member enrol payload must NEVER carry a
  // billingAgeGroupId. Age-band manual selection is a dependant-only
  // feature (categorical decision by disability, etc.); members'
  // per-person price change goes through the Custom-premium triple.
  // If a template regression re-introduces the picker, catch it here.
  // ------------------------------------------------------------------

  describe('member payload — age-band override is dependant-only', () => {
    it('omits billingAgeGroupId even if a stale form field is set', () => {
      const { comp, members } = instantiate('STANDARD');
      withValidRequiredFields(comp);
      // Simulate a stale draft carrying the field — payload builder
      // must not touch it.
      (comp.form as any).billingAgeGroupId = 'ag-child';
      comp.submit();
      expect('billingAgeGroupId' in members.enrollCalls[0]).toBeFalse();
    });
  });

  // ------------------------------------------------------------------
  // AI pricing suggestion helper — AI_DRIVEN tenants only.
  //
  // suggestPremium() is the "Suggest with AI" button handler. Two
  // hard preconditions (scheme + DoB) and three post-conditions
  // (amount pre-fill, effective-from default, response captured).
  // ------------------------------------------------------------------

  describe('AI pricing suggestion', () => {
    it('refuses to fire without a scheme (surfacing a specific toast)', () => {
      const { comp, pricing, toast } = instantiate('AI_DRIVEN');
      withValidRequiredFields(comp, { schemeId: '' });
      comp.suggestPremium();
      expect(pricing.suggestCalls.length).toBe(0);
      expect(toast.errors[0]).toContain('scheme');
    });

    it('refuses to fire without a date of birth', () => {
      const { comp, pricing, toast } = instantiate('AI_DRIVEN');
      withValidRequiredFields(comp, { dateOfBirth: '' });
      comp.suggestPremium();
      expect(pricing.suggestCalls.length).toBe(0);
      expect(toast.errors[0]).toContain('date of birth');
    });

    it('passes the scheme, DoB and every set risk signal on the request', () => {
      const { comp, pricing } = instantiate('AI_DRIVEN');
      withValidRequiredFields(comp);
      comp.form.smoker = true;
      comp.form.hasChronicConditions = true;
      comp.form.bmi = 32.5;
      comp.suggestPremium();
      expect(pricing.suggestCalls.length).toBe(1);
      const req = pricing.suggestCalls[0];
      expect(req.schemeId).toBe('sch-1');
      expect(req.dateOfBirth).toBe('1990-01-01');
      expect(req.gender).toBe('female');
      expect(req.smoker).toBeTrue();
      expect(req.hasChronicConditions).toBeTrue();
      expect(req.bmi).toBe(32.5);
    });

    it('omits unset optional risk signals so the backend defaults kick in', () => {
      // undefined here — not false/null — so the backend sees a
      // missing field rather than an explicit "no". Matters for the
      // gender multiplier gate.
      const { comp, pricing } = instantiate('AI_DRIVEN');
      withValidRequiredFields(comp, { gender: '' });
      comp.form.smoker = false;
      comp.form.hasChronicConditions = false;
      comp.form.bmi = null;
      comp.suggestPremium();
      const req = pricing.suggestCalls[0];
      expect(req.gender).toBeUndefined();
      expect(req.smoker).toBeUndefined();
      expect(req.hasChronicConditions).toBeUndefined();
      expect(req.bmi).toBeUndefined();
    });

    it('pre-fills the override amount + captures the response on success', () => {
      const { comp, pricing } = instantiate('AI_DRIVEN');
      pricing.response = {
        suggestedAmount: '175.50', currencyCode: 'USD',
        rationale: 'Adult + smoker', factors: ['Adult 30-40', 'Smoker'], stub: true,
      };
      withValidRequiredFields(comp);
      comp.suggestPremium();
      expect(comp.form.billingOverrideAmount).toBe(175.50);
      expect(comp.aiSuggestion?.stub).toBeTrue();
      expect(comp.aiSuggestion?.rationale).toContain('Adult');
      expect(comp.aiRequesting).toBeFalse();
    });

    it('defaults effective_from to the enrolment date when it is blank', () => {
      // Without this default the operator would immediately trip the
      // half-filled-triple guard right after clicking "Suggest".
      const { comp, pricing } = instantiate('AI_DRIVEN');
      pricing.response = { suggestedAmount: '150.00', currencyCode: 'USD',
                           rationale: '', factors: [], stub: true };
      withValidRequiredFields(comp, {
        enrollmentDate: '2026-09-01',
        billingOverrideEffectiveFrom: '',
      });
      comp.suggestPremium();
      expect(comp.form.billingOverrideEffectiveFrom).toBe('2026-09-01');
    });

    it('keeps an operator-picked effective_from intact', () => {
      const { comp, pricing } = instantiate('AI_DRIVEN');
      pricing.response = { suggestedAmount: '150.00', currencyCode: 'USD',
                           rationale: '', factors: [], stub: true };
      withValidRequiredFields(comp, {
        enrollmentDate: '2026-09-01',
        billingOverrideEffectiveFrom: '2026-12-01',
      });
      comp.suggestPremium();
      expect(comp.form.billingOverrideEffectiveFrom).toBe('2026-12-01');
    });

    it('surfaces service errors on the toast without corrupting form state', () => {
      const { comp, pricing, toast } = instantiate('AI_DRIVEN');
      pricing.fail = true;
      withValidRequiredFields(comp, { billingOverrideAmount: 42 });
      comp.suggestPremium();
      expect(toast.errors[0]).toContain('model down');
      expect(comp.aiRequesting).toBeFalse();
      // Existing manual amount stays put — only success paths mutate.
      expect(comp.form.billingOverrideAmount).toBe(42);
    });

    it('clears the current suggestion without touching the amount', () => {
      const { comp } = instantiate('AI_DRIVEN');
      comp.form.billingOverrideAmount = 175.50;
      comp.aiSuggestion = { suggestedAmount: '175.50', currencyCode: 'USD',
                            rationale: '', factors: [], stub: true } as any;
      comp.clearAiSuggestion();
      expect(comp.aiSuggestion).toBeNull();
      expect(comp.form.billingOverrideAmount).toBe(175.50);
    });
  });

  it('strips empty optional fields out of the payload', () => {
    const { comp, members } = instantiate();
    withValidRequiredFields(comp, { phone: '', address: '', groupId: '' });
    comp.submit();
    const sent = members.enrollCalls[0];
    expect(sent.phone).toBeUndefined();
    expect(sent.address).toBeUndefined();
    expect(sent.groupId).toBeUndefined();
    // Required fields stay on the payload.
    expect(sent.gender).toBe('female');
    expect(sent.nationalId).toBe('63-1234567');
    expect(sent.email).toBe('sarah@example.com');
    expect(sent.schemeId).toBe('sch-1');
  });
});
