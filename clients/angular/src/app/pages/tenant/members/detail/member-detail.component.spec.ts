import { CUSTOM_ELEMENTS_SCHEMA } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { MemberDetailComponent } from './member-detail.component';
import { Dependant, Member, MembersService } from '../../../../core/services/members.service';
import { ContributionsService } from '../../../../core/services/contributions.service';
import { GroupsService } from '../../../../core/services/groups.service';
import { PricingSuggestionService } from '../../../../core/services/pricing-suggestion.service';
import { TenantService } from '../../../../core/services/tenant.service';
import { ToastService } from '../../../../shared/components/toast/toast.service';

function makeMember(overrides: Partial<Member> = {}): Member {
  return {
    id: 'm-1',
    memberNumber: 'M-0001',
    firstName: 'Sarah',
    lastName: 'Doe',
    dateOfBirth: '1990-01-01',
    email: 'sarah@example.com',
    phone: '+263',
    status: 'active',
    groupId: null,
    schemeId: null,
    enrollmentDate: '2024-01-01',
    createdAt: '2024-01-01',
    ...overrides,
  };
}

function makeDependant(overrides: Partial<Dependant> = {}): Dependant {
  return {
    id: 'd-1', memberId: 'm-1',
    firstName: 'Lily', lastName: 'Doe',
    relationship: 'child', status: 'active',
    ...overrides,
  };
}

class StubMembers {
  member: Member = makeMember();
  dependants: Dependant[] = [makeDependant()];
  getByIdCalls = 0;
  updateCalls: any[] = [];
  addDependantCalls: any[] = [];
  updateDependantCalls: any[] = [];
  removeCalls: string[] = [];

  shouldFailGetMember = false;
  shouldFailUpdate = false;

  getById = (_id: string) => {
    this.getByIdCalls++;
    return this.shouldFailGetMember
      ? throwError(() => ({ error: { detail: 'boom' } }))
      : of(this.member);
  };
  update = (id: string, data: any) => {
    this.updateCalls.push({ id, data });
    return this.shouldFailUpdate
      ? throwError(() => ({ error: { detail: 'nope' } }))
      : of({ ...this.member, ...data });
  };
  getDependants = (_id: string) => of(this.dependants);
  addDependant = (data: any) => {
    this.addDependantCalls.push(data);
    return of(makeDependant({ id: 'd-new', ...data }));
  };
  updateDependant = (id: string, data: any) => {
    this.updateDependantCalls.push({ id, data });
    return of({ ...this.dependants.find(d => d.id === id)!, ...data });
  };
  deactivateCalls: Array<{ id: string; effectiveDate: string | null }> = [];
  deactivateDependant = (id: string, effectiveDate: string | null) => {
    this.deactivateCalls.push({ id, effectiveDate });
    return of({ ...this.dependants.find(d => d.id === id)!, status: 'deactivated',
                deactivationEffectiveDate: effectiveDate });
  };
  activate  = () => of(this.member);
  suspend   = () => of(this.member);
  terminate = () => of(this.member);
}

class StubToast {
  successes: string[] = []; errors: string[] = [];
  success = (m: string) => this.successes.push(m);
  error   = (m: string) => this.errors.push(m);
}

class StubRouter { navigated: any[] = []; navigate = (cmds: any[]) => this.navigated.push(cmds); }

class StubGroups   { findById = (_id: string) => of({ id: _id, name: 'Acme', status: 'ACTIVE' } as any); }
class StubContribs {
  getSchemeById = (_id: string) => of({ id: _id, name: 'Gold', status: 'active', effectiveDate: '' } as any);
  // Age-group support for the new picker + suggestPremium tests.
  ageGroupsByScheme = new Map<string, any[]>();
  ageGroupsFail = false;
  getAgeGroupsByScheme = (id: string) => {
    if (this.ageGroupsFail) return throwError(() => new Error('boom'));
    return of<any[]>(this.ageGroupsByScheme.get(id) ?? []);
  };
}
class StubPricingSuggestion {
  suggestCalls: any[] = [];
  response: any = {
    suggestedAmount: '175.50', currencyCode: 'USD',
    rationale: 'Adult + smoker', factors: ['Adult 30-40'], stub: true,
  };
  fail = false;
  suggest = (req: any) => {
    this.suggestCalls.push(req);
    return this.fail
      ? throwError(() => ({ error: { detail: 'model down' } }))
      : of<any>(this.response);
  };
}

function instantiate(id: string | null = 'm-1', pricingModel: string = 'STANDARD') {
  const route = { snapshot: { paramMap: { get: (_k: string) => id } } } as any;
  const members = new StubMembers();
  const groups = new StubGroups();
  const contribs = new StubContribs();
  const toast = new StubToast();
  const router = new StubRouter();
  const tenantSvc = { getTenant: () => ({ pricingModel }) };
  const pricing = new StubPricingSuggestion();
  const comp = new MemberDetailComponent(
    members as unknown as MembersService,
    groups as unknown as GroupsService,
    contribs as unknown as ContributionsService,
    route,
    router as any,
    toast as unknown as ToastService,
    tenantSvc as any,
    pricing as any,
  );
  return { comp, members, toast, router, contribs, pricing };
}

describe('MemberDetailComponent', () => {
  beforeEach(() => spyOn(window, 'confirm').and.returnValue(true));

  it('loads member + dependants on init', () => {
    const { comp, members } = instantiate();
    comp.ngOnInit();
    expect(members.getByIdCalls).toBe(1);
    expect(comp.member?.firstName).toBe('Sarah');
    expect(comp.form.firstName).toBe('Sarah');
    expect(comp.dependants.length).toBe(1);
  });

  it('updates the member via save() and surfaces a success toast', () => {
    const { comp, members, toast } = instantiate();
    comp.ngOnInit();
    comp.form.email = 'new@example.com';
    comp.save();
    expect(members.updateCalls[0].data.email).toBe('new@example.com');
    expect(toast.successes[0]).toBe('Member updated');
  });

  it('shows an error banner when save fails', () => {
    const { comp, members } = instantiate();
    comp.ngOnInit();
    members.shouldFailUpdate = true;
    comp.save();
    expect(comp.errorMessage).toBe('nope');
  });

  it('adds a new dependant via the inline form', () => {
    const { comp, members } = instantiate();
    comp.ngOnInit();
    comp.startAddDependant();
    comp.dependantForm = {
      firstName: 'Joe', lastName: 'Doe', dateOfBirth: '2020-05-01',
      gender: 'male', relationship: 'child', nationalId: '63-1234567', billingOverrideAmount: null, billingOverrideReason: '', billingOverrideEffectiveFrom: '', billingAgeGroupId: '', smoker: false, hasChronicConditions: false, bmi: null,
    };
    comp.saveDependant();
    expect(members.addDependantCalls[0].memberId).toBe('m-1');
    expect(comp.dependants.length).toBe(2);
    expect(comp.editingDependantId).toBeNull();
  });

  it('rejects new-dependant save when national ID is missing', () => {
    const { comp, members, toast } = instantiate();
    comp.ngOnInit();
    comp.startAddDependant();
    comp.dependantForm = {
      firstName: 'A', lastName: 'B', dateOfBirth: '2020-01-01',
      gender: 'male', relationship: 'child', nationalId: '',
      billingOverrideAmount: null, billingOverrideReason: '', billingOverrideEffectiveFrom: '', billingAgeGroupId: '', smoker: false, hasChronicConditions: false, bmi: null,
    };
    comp.saveDependant();
    expect(members.addDependantCalls.length).toBe(0);
    expect(toast.errors[0]).toContain('national ID');
  });

  it('updates an existing dependant via updateDependant', () => {
    const { comp, members } = instantiate();
    comp.ngOnInit();
    comp.editDependant(comp.dependants[0]);
    comp.dependantForm = {
      ...comp.dependantForm,
      gender: 'female',
      nationalId: '63-7654321',
      relationship: 'spouse',
    };
    comp.saveDependant();
    expect(members.updateDependantCalls[0].id).toBe('d-1');
    expect(members.updateDependantCalls[0].data.relationship).toBe('spouse');
  });

  it('deactivates a dependant with today as the default effective date', () => {
    // V046: the prompt is seeded with today. Cancel = abort;
    // empty answer = today; a valid ISO = that date. Here we simulate
    // the operator hitting Enter on the seeded value.
    const today = new Date().toISOString().slice(0, 10);
    const promptSpy = spyOn(window, 'prompt').and.returnValue(today);
    const { comp, members } = instantiate();
    comp.ngOnInit();
    comp.deactivateDependant(comp.dependants[0]);
    expect(promptSpy).toHaveBeenCalled();
    expect(members.deactivateCalls[0].id).toBe('d-1');
    expect(members.deactivateCalls[0].effectiveDate).toBe(today);
    expect(comp.dependants[0].status).toBe('deactivated');
  });

  it('aborts the deactivation when the operator cancels the prompt', () => {
    // Cancel returns null — no service call, no state change.
    spyOn(window, 'prompt').and.returnValue(null);
    const { comp, members } = instantiate();
    comp.ngOnInit();
    comp.deactivateDependant(comp.dependants[0]);
    expect(members.deactivateCalls.length).toBe(0);
    expect(comp.dependants[0].status).toBe('active');
  });

  it('rejects a garbled ISO date with a toast (no service call)', () => {
    // Malformed date → friendly toast instead of a 400 round-trip.
    spyOn(window, 'prompt').and.returnValue('yesterday');
    const { comp, members, toast } = instantiate();
    comp.ngOnInit();
    comp.deactivateDependant(comp.dependants[0]);
    expect(members.deactivateCalls.length).toBe(0);
    expect(toast.errors[0]).toContain('YYYY-MM-DD');
  });

  it('accepts an operator-picked future date and forwards it to the service', () => {
    spyOn(window, 'prompt').and.returnValue('2026-09-15');
    const { comp, members } = instantiate();
    comp.ngOnInit();
    comp.deactivateDependant(comp.dependants[0]);
    expect(members.deactivateCalls[0].effectiveDate).toBe('2026-09-15');
  });

  it('rejects dependant save when required fields are missing', () => {
    const { comp, members, toast } = instantiate();
    comp.ngOnInit();
    comp.startAddDependant();
    comp.dependantForm = { ...comp.dependantForm, firstName: '' };
    comp.saveDependant();
    expect(members.addDependantCalls.length).toBe(0);
    expect(toast.errors[0]).toContain('missing');
  });

  it('rejects new-dependant save when gender is blank', () => {
    const { comp, members, toast } = instantiate();
    comp.ngOnInit();
    comp.startAddDependant();
    comp.dependantForm = {
      firstName: 'A', lastName: 'B', dateOfBirth: '2020-01-01',
      gender: '', relationship: 'child', nationalId: '63-1234567',
      billingOverrideAmount: null, billingOverrideReason: '', billingOverrideEffectiveFrom: '', billingAgeGroupId: '', smoker: false, hasChronicConditions: false, bmi: null,
    };
    comp.saveDependant();
    expect(members.addDependantCalls.length).toBe(0);
    expect(toast.errors[0]).toContain('gender');
  });

  it('rejects dependant edit when gender is cleared', () => {
    const { comp, members, toast } = instantiate();
    comp.ngOnInit();
    comp.editDependant(comp.dependants[0]);
    comp.dependantForm = { ...comp.dependantForm, gender: '', nationalId: '63-1234567' };
    comp.saveDependant();
    expect(members.updateDependantCalls.length).toBe(0);
    expect(toast.errors[0]).toContain('gender');
  });

  it('rejects dependant edit when national ID is cleared', () => {
    const { comp, members, toast } = instantiate();
    comp.ngOnInit();
    comp.editDependant(comp.dependants[0]);
    comp.dependantForm = { ...comp.dependantForm, gender: 'female', nationalId: '' };
    comp.saveDependant();
    expect(members.updateDependantCalls.length).toBe(0);
    expect(toast.errors[0]).toContain('national ID');
  });

  it('pluralises the missing-fields message when more than one is blank', () => {
    const { comp, toast } = instantiate();
    comp.ngOnInit();
    comp.startAddDependant();
    comp.dependantForm = {
      firstName: 'A', lastName: 'B', dateOfBirth: '2020-01-01',
      gender: '', relationship: 'child', nationalId: '',
      billingOverrideAmount: null, billingOverrideReason: '', billingOverrideEffectiveFrom: '', billingAgeGroupId: '', smoker: false, hasChronicConditions: false, bmi: null,
    };
    comp.saveDependant();
    expect(toast.errors[0]).toContain('Required fields missing');
    expect(toast.errors[0]).toContain('gender');
    expect(toast.errors[0]).toContain('national ID');
  });

  // ------------------------------------------------------------------
  // Dependant custom-pricing (V030 INDIVIDUAL model)
  //
  // Same shape as the member override. The frontend gate keeps
  // STANDARD-model tenants from silently sending the triple even if
  // a stale draft carries values; the guard for a half-filled triple
  // mirrors the backend so operators see a friendly toast instead of
  // a 400 round-trip.
  // ------------------------------------------------------------------

  describe('dependant custom-premium', () => {
    it('is omitted from the addDependant payload for STANDARD-model tenants', () => {
      const { comp, members } = instantiate('m-1', 'STANDARD');
      comp.ngOnInit();
      comp.startAddDependant();
      comp.dependantForm = {
        firstName: 'Joe', lastName: 'Doe', dateOfBirth: '2020-05-01',
        gender: 'male', relationship: 'child', nationalId: '63-1234567',
        // Stale draft state — should not travel.
        billingOverrideAmount: 40, billingOverrideReason: 'discount',
        billingOverrideEffectiveFrom: '2026-08-01', billingAgeGroupId: '', smoker: false, hasChronicConditions: false, bmi: null,
      };

      comp.saveDependant();

      const sent = members.addDependantCalls[0];
      expect('billingOverrideAmount' in sent).toBeFalse();
      expect('billingOverrideReason' in sent).toBeFalse();
      expect('billingOverrideEffectiveFrom' in sent).toBeFalse();
    });

    it('is exposed to INDIVIDUAL-model tenants on the addDependant payload', () => {
      const { comp, members } = instantiate('m-1', 'INDIVIDUAL');
      comp.ngOnInit();
      comp.startAddDependant();
      comp.dependantForm = {
        firstName: 'Joe', lastName: 'Doe', dateOfBirth: '2020-05-01',
        gender: 'male', relationship: 'child', nationalId: '63-1234567',
        billingOverrideAmount: 40, billingOverrideReason: 'student rate',
        billingOverrideEffectiveFrom: '2026-08-01', billingAgeGroupId: '', smoker: false, hasChronicConditions: false, bmi: null,
      };

      comp.saveDependant();

      const sent = members.addDependantCalls[0];
      expect(sent.billingOverrideAmount).toBe(40);
      expect(sent.billingOverrideReason).toBe('student rate');
      expect(sent.billingOverrideEffectiveFrom).toBe('2026-08-01');
    });

    it('rejects a half-filled triple (amount without effective_from)', () => {
      const { comp, members, toast } = instantiate('m-1', 'INDIVIDUAL');
      comp.ngOnInit();
      comp.startAddDependant();
      comp.dependantForm = {
        firstName: 'Joe', lastName: 'Doe', dateOfBirth: '2020-05-01',
        gender: 'male', relationship: 'child', nationalId: '63-1234567',
        billingOverrideAmount: 40, billingOverrideReason: 'student rate',
        billingOverrideEffectiveFrom: '', billingAgeGroupId: '', smoker: false, hasChronicConditions: false, bmi: null,
      };

      comp.saveDependant();

      expect(members.addDependantCalls.length).toBe(0);
      expect(toast.errors[0]).toContain('effective_from');
    });

    it('travels on the updateDependant payload when editing', () => {
      // Edit-path — the operator adjusts an existing dependant's
      // custom premium. Regression here would silently swallow the
      // change on save.
      const { comp, members } = instantiate('m-1', 'INDIVIDUAL');
      comp.ngOnInit();
      comp.editDependant(comp.dependants[0]);
      // Fill the required fields the stub dependant leaves blank so
      // the validation gate doesn't short-circuit the save.
      comp.dependantForm = {
        ...comp.dependantForm,
        gender: 'female',
        nationalId: '63-1234567',
        billingOverrideAmount: 50,
        billingOverrideReason: 'hardship',
        billingOverrideEffectiveFrom: '2026-09-01', billingAgeGroupId: '', smoker: false, hasChronicConditions: false, bmi: null,
      };

      comp.saveDependant();

      const sent = members.updateDependantCalls[0].data;
      expect(sent.billingOverrideAmount).toBe(50);
      expect(sent.billingOverrideEffectiveFrom).toBe('2026-09-01');
    });
  });

  // ------------------------------------------------------------------
  // Pricing model gates on the detail page.
  //
  // Both the member Custom-premium section AND the dependant one share
  // .individualPricing. Only the "Suggest with AI" widgets gate on the
  // narrower .aiSuggestionsEnabled getter.
  // ------------------------------------------------------------------

  describe('pricing model gates', () => {
    it('treats AI_DRIVEN as hybrid — individualPricing is true', () => {
      const { comp } = instantiate('m-1', 'AI_DRIVEN');
      expect(comp.individualPricing).toBeTrue();
    });

    it('gates aiSuggestionsEnabled ONLY on AI_DRIVEN', () => {
      expect(instantiate('m-1', 'AI_DRIVEN').comp.aiSuggestionsEnabled).toBeTrue();
      expect(instantiate('m-1', 'INDIVIDUAL').comp.aiSuggestionsEnabled).toBeFalse();
      expect(instantiate('m-1', 'STANDARD').comp.aiSuggestionsEnabled).toBeFalse();
    });
  });

  // ------------------------------------------------------------------
  // Age-group override — DEPENDANT-ONLY feature.
  //
  // Members don't have an age-band manual override — their band is
  // resolved from DoB, and any per-person price change goes through
  // the Custom-premium override. Dependants CAN be re-classified to a
  // different band (e.g. a child with a disability who ages out of
  // the child band by DoB but should stay on the child rate).
  //
  // The picker's option list is shared between member scheme + the
  // dependant collapsible: when the member's scheme changes, the
  // option list refreshes so the dependant picker paints the new
  // scheme's bands.
  // ------------------------------------------------------------------

  describe('age-group override (dependant-only)', () => {
    it('loads scheme-scoped ageGroupOptions on member load so the dependant collapsible can consume them', () => {
      const { comp, members, contribs } = instantiate();
      members.member = makeMember({ schemeId: 'sch-9' });
      contribs.ageGroupsByScheme.set('sch-9', [
        { id: 'ag-a', name: 'Adult', minAge: 18, maxAge: 64 },
      ]);
      comp.ngOnInit();
      expect(comp.ageGroupOptions.length).toBe(1);
      expect(comp.ageGroupOptions[0].value).toBe('ag-a');
    });

    it('leaves ageGroupOptions empty when the loaded member has no scheme', () => {
      const { comp, members } = instantiate();
      members.member = makeMember({ schemeId: null });
      comp.ngOnInit();
      expect(comp.ageGroupOptions.length).toBe(0);
    });

    it('refreshes ageGroupOptions on onSchemeChange so the dependant picker follows the new scheme', () => {
      const { comp, contribs } = instantiate();
      comp.ngOnInit();
      contribs.ageGroupsByScheme.set('sch-new', [
        { id: 'ag-x', name: 'X', minAge: 0, maxAge: 99 },
      ]);
      comp.onSchemeChange('sch-new');
      expect(comp.ageGroupOptions.length).toBe(1);
    });

    it('member update payload never carries billingAgeGroupId (dependant-only feature)', () => {
      // Regression guard: even if a stale field slips into form state
      // via any means, the payload builder must omit it. Age-band
      // manual selection is not a member-side control.
      const { comp, members } = instantiate('m-1', 'STANDARD');
      comp.ngOnInit();
      (comp.form as any).billingAgeGroupId = 'ag-should-not-leak';
      comp.save();
      expect('billingAgeGroupId' in members.updateCalls[0].data).toBeFalse();
    });

    it('sends billingAgeGroupId on the addDependant payload for any pricing model', () => {
      const { comp, members } = instantiate('m-1', 'STANDARD');
      comp.ngOnInit();
      comp.startAddDependant();
      comp.dependantForm = {
        firstName: 'Joe', lastName: 'Doe', dateOfBirth: '2020-05-01',
        gender: 'male', relationship: 'child', nationalId: '63-1234567',
        billingOverrideAmount: null, billingOverrideReason: '', billingOverrideEffectiveFrom: '',
        billingAgeGroupId: 'ag-child',
        smoker: false, hasChronicConditions: false, bmi: null,
      };
      comp.saveDependant();
      expect(members.addDependantCalls[0].billingAgeGroupId).toBe('ag-child');
    });

    it('sends billingAgeGroupId on the updateDependant payload when editing', () => {
      const { comp, members } = instantiate();
      comp.ngOnInit();
      comp.editDependant(comp.dependants[0]);
      comp.dependantForm = {
        ...comp.dependantForm,
        gender: 'female',
        nationalId: '63-1234567',
        billingAgeGroupId: 'ag-senior',
      };
      comp.saveDependant();
      expect(members.updateDependantCalls[0].data.billingAgeGroupId).toBe('ag-senior');
    });
  });

  // ------------------------------------------------------------------
  // AI pricing suggestion on the detail page — member + dependant.
  //
  // Two separate suggestion streams (memberAiSuggestion vs
  // dependantAiSuggestion) so an operator can suggest for both
  // without one clobbering the other's rationale display.
  // ------------------------------------------------------------------

  describe('AI pricing suggestion — member', () => {
    it('refuses to fire without a scheme id', () => {
      const { comp, members, pricing, toast } = instantiate('m-1', 'AI_DRIVEN');
      members.member = makeMember({ schemeId: null, dateOfBirth: '1990-01-01' });
      comp.ngOnInit();
      comp.suggestMemberPremium();
      expect(pricing.suggestCalls.length).toBe(0);
      expect(toast.errors[0]).toContain('scheme');
    });

    it('passes scheme + DoB + risk signals + gender on the request', () => {
      const { comp, members, pricing } = instantiate('m-1', 'AI_DRIVEN');
      members.member = makeMember({ schemeId: 'sch-1', dateOfBirth: '1985-06-30' });
      comp.ngOnInit();
      comp.form.gender = 'female';
      comp.memberRiskSignals.smoker = true;
      comp.memberRiskSignals.hasChronicConditions = true;
      comp.memberRiskSignals.bmi = 31.4;
      comp.suggestMemberPremium();
      const req = pricing.suggestCalls[0];
      expect(req.schemeId).toBe('sch-1');
      expect(req.dateOfBirth).toBe('1985-06-30');
      expect(req.gender).toBe('female');
      expect(req.smoker).toBeTrue();
      expect(req.hasChronicConditions).toBeTrue();
      expect(req.bmi).toBe(31.4);
    });

    it('pre-fills the override amount + captures the response on success', () => {
      const { comp, members, pricing } = instantiate('m-1', 'AI_DRIVEN');
      members.member = makeMember({ schemeId: 'sch-1', dateOfBirth: '1990-01-01' });
      pricing.response = {
        suggestedAmount: '220.00', currencyCode: 'USD',
        rationale: 'Adult + smoker', factors: ['Adult 30-40'], stub: true,
      };
      comp.ngOnInit();
      comp.suggestMemberPremium();
      expect(comp.form.billingOverrideAmount).toBe(220);
      expect(comp.memberAiSuggestion?.stub).toBeTrue();
      expect(comp.memberAiRequesting).toBeFalse();
    });

    it('defaults effective_from to a valid ISO date when blank', () => {
      // Without a default the operator would trip the half-filled-
      // triple guard right after clicking Suggest. The exact day
      // depends on the local timezone (toISOString() is UTC and the
      // component builds a local Date), so pin the shape only.
      const { comp, members } = instantiate('m-1', 'AI_DRIVEN');
      members.member = makeMember({ schemeId: 'sch-1', dateOfBirth: '1990-01-01' });
      comp.ngOnInit();
      comp.form.billingOverrideEffectiveFrom = '';
      comp.suggestMemberPremium();
      expect(comp.form.billingOverrideEffectiveFrom).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    });

    it('keeps an operator-picked effective_from intact', () => {
      const { comp, members } = instantiate('m-1', 'AI_DRIVEN');
      members.member = makeMember({ schemeId: 'sch-1', dateOfBirth: '1990-01-01' });
      comp.ngOnInit();
      comp.form.billingOverrideEffectiveFrom = '2026-12-01';
      comp.suggestMemberPremium();
      expect(comp.form.billingOverrideEffectiveFrom).toBe('2026-12-01');
    });

    it('surfaces service errors on the toast without corrupting form state', () => {
      const { comp, members, pricing, toast } = instantiate('m-1', 'AI_DRIVEN');
      members.member = makeMember({ schemeId: 'sch-1', dateOfBirth: '1990-01-01' });
      pricing.fail = true;
      comp.ngOnInit();
      comp.form.billingOverrideAmount = 99;
      comp.suggestMemberPremium();
      expect(toast.errors[0]).toContain('model down');
      expect(comp.memberAiRequesting).toBeFalse();
      // Prior manual amount is preserved on failure.
      expect(comp.form.billingOverrideAmount).toBe(99);
    });

    it('clears the current member suggestion without touching the amount', () => {
      const { comp } = instantiate('m-1', 'AI_DRIVEN');
      comp.form.billingOverrideAmount = 220;
      comp.memberAiSuggestion = { stub: true } as any;
      comp.clearMemberAiSuggestion();
      expect(comp.memberAiSuggestion).toBeNull();
      expect(comp.form.billingOverrideAmount).toBe(220);
    });
  });

  describe('AI pricing suggestion — dependant', () => {
    it('refuses to fire without the dependant DoB', () => {
      const { comp, pricing, toast } = instantiate('m-1', 'AI_DRIVEN');
      comp.ngOnInit();
      comp.form.schemeId = 'sch-1';
      comp.startAddDependant();
      comp.dependantForm.dateOfBirth = '';
      comp.suggestDependantPremium();
      expect(pricing.suggestCalls.length).toBe(0);
      expect(toast.errors[0]).toContain('date of birth');
    });

    it('sends the dependant DoB + risk signals on the request', () => {
      const { comp, pricing } = instantiate('m-1', 'AI_DRIVEN');
      comp.ngOnInit();
      comp.form.schemeId = 'sch-1';
      comp.startAddDependant();
      comp.dependantForm.dateOfBirth = '2010-04-04';
      comp.dependantForm.gender = 'male';
      comp.dependantForm.smoker = false;
      comp.dependantForm.hasChronicConditions = true;
      comp.dependantForm.bmi = 22.0;
      comp.suggestDependantPremium();
      const req = pricing.suggestCalls[0];
      expect(req.schemeId).toBe('sch-1');
      expect(req.dateOfBirth).toBe('2010-04-04');
      expect(req.gender).toBe('male');
      expect(req.hasChronicConditions).toBeTrue();
      expect(req.bmi).toBe(22.0);
      // Zero-value booleans should be omitted so the backend defaults kick in.
      expect(req.smoker).toBeUndefined();
    });

    it('pre-fills the dependant override amount on success', () => {
      const { comp, pricing } = instantiate('m-1', 'AI_DRIVEN');
      pricing.response = { suggestedAmount: '38.00', currencyCode: 'USD',
                           rationale: '', factors: [], stub: true };
      comp.ngOnInit();
      comp.form.schemeId = 'sch-1';
      comp.startAddDependant();
      comp.dependantForm.dateOfBirth = '2010-04-04';
      comp.suggestDependantPremium();
      expect(comp.dependantForm.billingOverrideAmount).toBe(38);
      expect(comp.dependantAiSuggestion?.stub).toBeTrue();
    });

    it('clears the dependant AI suggestion when the operator opens edit', () => {
      // Prevent a stale suggestion carrying over from a previous edit
      // session — the operator opens a different dependant, they
      // shouldn't see the old rationale.
      const { comp } = instantiate();
      comp.ngOnInit();
      comp.dependantAiSuggestion = { stub: true } as any;
      comp.editDependant(comp.dependants[0]);
      expect(comp.dependantAiSuggestion).toBeNull();
    });

    it('clears the current dependant suggestion without touching the amount', () => {
      const { comp } = instantiate();
      comp.dependantForm.billingOverrideAmount = 38;
      comp.dependantAiSuggestion = { stub: true } as any;
      comp.clearDependantAiSuggestion();
      expect(comp.dependantAiSuggestion).toBeNull();
      expect(comp.dependantForm.billingOverrideAmount).toBe(38);
    });
  });

  // ------------------------------------------------------------------
  // Rendered-template guard: the dependant action button reads
  // "Terminate" (matches the member Terminate UI). Guards regressions
  // back to "Deactivate" or the historic "Remove" wording — either
  // would break user expectations and the tenant admin docs.
  // ------------------------------------------------------------------

  describe('rendered template', () => {
    it('renders the dependant action button with the "Terminate" label', async () => {
      // Use CUSTOM_ELEMENTS_SCHEMA so nested standalone components
      // (EntityPickerComponent, SelectComponent, IconComponent) don't
      // need their own DI graph — this test only cares about the
      // parent template's text content.
      await TestBed.configureTestingModule({
        imports: [MemberDetailComponent],
        providers: [
          provideHttpClient(),
          provideHttpClientTesting(),
          provideRouter([]),
          { provide: MembersService,           useValue: new StubMembers() },
          { provide: GroupsService,            useValue: new StubGroups() },
          { provide: ContributionsService,     useValue: new StubContribs() },
          { provide: ToastService,             useValue: new StubToast() },
          { provide: TenantService,            useValue: { getTenant: () => ({ pricingModel: 'STANDARD' }) } },
          { provide: PricingSuggestionService, useValue: new StubPricingSuggestion() },
          { provide: ActivatedRoute,           useValue: { snapshot: { paramMap: { get: (_k: string) => 'm-1' } } } },
        ],
        schemas: [CUSTOM_ELEMENTS_SCHEMA],
      }).compileComponents();

      const fixture = TestBed.createComponent(MemberDetailComponent);
      fixture.detectChanges();
      const text: string = fixture.nativeElement.textContent ?? '';
      expect(text).toContain('Terminate');
      // Explicit negatives — the historic wording must never come back.
      expect(text).not.toContain('Deactivate');
      // "Remove" appears only if the old button label leaked back.
      // Guard the button vicinity by asserting the exact button text.
      expect(text).not.toMatch(/\bRemove\b/);
    });
  });

  it('only shows status-actions for the current legal transitions', () => {
    const { comp } = instantiate();
    comp.member = makeMember({ status: 'active' });
    expect(comp.canActivate()).toBeFalse();
    expect(comp.canSuspend()).toBeTrue();
    expect(comp.canTerminate()).toBeTrue();

    comp.member = makeMember({ status: 'terminated' });
    expect(comp.canActivate()).toBeFalse();
    expect(comp.canSuspend()).toBeFalse();
    expect(comp.canTerminate()).toBeFalse();
  });
});
