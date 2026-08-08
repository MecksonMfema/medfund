import { BehaviorSubject, of, throwError } from 'rxjs';
import { ActivatedRoute } from '@angular/router';
import { SubmitClaimComponent } from './submit-claim.component';
import {
  ClaimSubmissionResponse,
  ClaimsService,
  SubmitClaimPayload,
} from '../../../../core/services/claims.service';
import { ClaimsConfigService } from '../../../../core/services/claims-config.service';
import { ContributionsService, Scheme } from '../../../../core/services/contributions.service';
import { CurrencyService, TenantCurrencyConfig } from '../../../../core/services/currency.service';
import { Member, MembersService } from '../../../../core/services/members.service';
import { TenantService } from '../../../../core/services/tenant.service';
import { ToastService } from '../../../../shared/components/toast/toast.service';

/**
 * Component-level spec for SubmitClaimComponent. Drives the class
 * directly (no TestBed) — the interesting logic (per-line adaptive
 * rendering state + payload assembly + envelope handling) lives on
 * the class, not in the template.
 *
 * <p>Coverage focus:
 * <ul>
 *   <li>Scheme selection sets the derived insurance line and toggles
 *       between the line-item body and the single-item attribute body.</li>
 *   <li>Payload construction honours the "batching is opt-in" contract:
 *       {@code batchNumber} rides only when the operator explicitly
 *       ticked the toggle AND typed a tag.</li>
 *   <li>Response envelope surfacing — verification code + expiry appear
 *       on the confirmation strip.</li>
 * </ul>
 */
describe('SubmitClaimComponent', () => {
  let claims: jasmine.SpyObj<ClaimsService>;
  let config: jasmine.SpyObj<ClaimsConfigService>;
  let contributions: jasmine.SpyObj<ContributionsService>;
  let members: jasmine.SpyObj<MembersService>;
  let currency: jasmine.SpyObj<CurrencyService>;
  let tenant: TenantService;
  let toast: jasmine.SpyObj<ToastService>;
  let router: jasmine.SpyObj<{ navigate: (...args: any[]) => any }>;
  let route: ActivatedRoute;
  let component: SubmitClaimComponent;

  const healthScheme = (): Scheme => ({
    id: 'scheme-1', name: 'Health Basic', currencyCode: 'USD',
    schemeType: 'medical_aid', insuranceLine: 'HEALTH',
    status: 'active', effectiveDate: '2026-01-01',
  } as Scheme);

  const vehicleScheme = (): Scheme => ({
    id: 'scheme-2', name: 'Motor Comprehensive', currencyCode: 'USD',
    schemeType: 'comprehensive', insuranceLine: 'VEHICLE',
    status: 'active', effectiveDate: '2026-01-01',
  } as Scheme);

  const envelope = (): ClaimSubmissionResponse => ({
    claim: {
      id: 'c-1', claimNumber: 'CLM-000123', memberId: 'm-1', providerId: 'p-1',
      schemeId: 'scheme-1', claimType: 'medical', insuranceLine: 'HEALTH',
      status: 'VERIFIED', serviceDate: '2026-07-11', claimedAmount: '500.00',
      currencyCode: 'USD', createdAt: '2026-07-11T00:00:00Z',
    },
    batchNumber: null,
  });

  beforeEach(() => {
    claims = jasmine.createSpyObj<ClaimsService>('ClaimsService', ['submit']);
    config = jasmine.createSpyObj<ClaimsConfigService>('ClaimsConfigService', ['listModifiers', 'searchCodes']);
    contributions = jasmine.createSpyObj<ContributionsService>('ContributionsService', ['getSchemeById']);
    members = jasmine.createSpyObj<MembersService>('MembersService', ['getById']);
    // Default: every member the picker returns is enrolled on scheme-1.
    // Tests that need "no scheme" override this locally.
    members.getById.and.callFake(id => of({ id, schemeId: 'scheme-1' } as Member));
    // Default: any scheme lookup returns a HEALTH scheme. Tests that
    // exercise VEHICLE / LIFE / FUNERAL override with a specific scheme
    // via `.and.returnValue(of(...))`.
    contributions.getSchemeById.and.callFake(() => of(healthScheme()));
    currency = jasmine.createSpyObj<CurrencyService>('CurrencyService', ['listForTenant']);
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error', 'warning']);
    router = jasmine.createSpyObj('Router', ['navigate']);

    // Default happy-path stubs.
    config.listModifiers.and.returnValue(of([]));
    config.searchCodes.and.returnValue(of([]));
    currency.listForTenant.and.returnValue(of<TenantCurrencyConfig[]>([]));

    // Hand-rolled TenantService — the component only reads getTenant().
    tenant = { getTenant: () => ({ id: 't-1', insuranceLines: ['HEALTH', 'VEHICLE'] }) } as unknown as TenantService;

    // Minimal ActivatedRoute — the component reads data.presetClaimType.
    route = { snapshot: { data: {} } } as unknown as ActivatedRoute;

    component = new SubmitClaimComponent(
      claims, config, contributions, currency, members, tenant as any,
      toast, route, router as any,
    );
    component.ngOnInit();
  });

  // ──────────────────────────────────────────────────────────────────
  // Adaptive body: line-item vs single-item based on scheme's line
  // ──────────────────────────────────────────────────────────────────

  describe('scheme change → adaptive body', () => {
    it('HEALTH scheme keeps the line-item table and exposes tariff/modifier fields', () => {
      contributions.getSchemeById.and.returnValue(of(healthScheme()));

      component.onSchemeIdChange('scheme-1');

      expect(component.activeLine).toBe('HEALTH');
      expect(component.usesItemLines).toBeTrue();
      expect(component.hasField('tariffCodes')).toBeTrue();
      expect(component.hasField('modifiers')).toBeTrue();
      // Currency snaps to the scheme's currency.
      expect(component.form.currencyCode).toBe('USD');
    });

    it('VEHICLE scheme swaps to the single-item body and hides tariff fields', () => {
      contributions.getSchemeById.and.returnValue(of(vehicleScheme()));

      component.onSchemeIdChange('scheme-2');

      expect(component.activeLine).toBe('VEHICLE');
      expect(component.usesItemLines)
        .withContext('VEHICLE isn\'t itemised — the FormArray must not render')
        .toBeFalse();
      expect(component.hasField('vehicleRegistration')).toBeTrue();
      expect(component.hasField('incidentLocation')).toBeTrue();
      expect(component.hasField('tariffCodes')).toBeFalse();
      // Reset stale HEALTH state — no rogue empty line hanging around.
      expect(component.lines).toEqual([]);
    });

    it('clearing the scheme resets the derived line', () => {
      contributions.getSchemeById.and.returnValue(of(healthScheme()));
      component.onSchemeIdChange('scheme-1');
      component.onSchemeIdChange(null);
      expect(component.activeLine).toBeNull();
    });

    it('HEALTH sets provider REQUIRED — picker always shown, mandatory on submit', () => {
      // V066: the service provider is load-bearing for adjudication
      // (specialty, network, tariffs) regardless of who receives the
      // payout. Member-reimbursement is a payee-routing choice, not a
      // signal to omit the provider.
      contributions.getSchemeById.and.returnValue(of(healthScheme()));
      component.onSchemeIdChange('scheme-1');
      expect(component.providerMode).toBe('REQUIRED');
      expect(component.showProviderPicker()).toBeTrue();
      expect(component.isProviderRequired()).toBeTrue();
    });

    it('VEHICLE also REQUIRED — same shape as HEALTH', () => {
      contributions.getSchemeById.and.returnValue(of(vehicleScheme()));
      component.onSchemeIdChange('scheme-2');
      expect(component.providerMode).toBe('REQUIRED');
      expect(component.showProviderPicker()).toBeTrue();
      expect(component.isProviderRequired()).toBeTrue();
    });

    it('LIFE is FORBIDDEN — picker hides, payeeType snaps to MEMBER, stale provider scrubbed', () => {
      // A stale providerId from a previous HEALTH scheme selection must
      // be cleared when switching to LIFE — otherwise it rides silently
      // into the payload and the backend rejects the whole submit.
      contributions.getSchemeById.and.returnValue(of(healthScheme()));
      component.onSchemeIdChange('scheme-1');
      component.providerId = 'p-existing';

      const lifeScheme = { ...healthScheme(), id: 'scheme-life', insuranceLine: 'LIFE', schemeType: 'term_life' } as Scheme;
      contributions.getSchemeById.and.returnValue(of(lifeScheme));
      component.onSchemeIdChange('scheme-life');

      expect(component.providerMode).toBe('FORBIDDEN');
      expect(component.showProviderPicker()).toBeFalse();
      expect(component.providerId).toBeNull();
      expect(component.payeeType).toBe('MEMBER');
    });

    it('FUNERAL stays OPTIONAL — picker shown but not required (tenant may be the funeral director)', () => {
      const funeralScheme = { ...healthScheme(), id: 'scheme-fun', insuranceLine: 'FUNERAL', schemeType: 'funeral_benefit' } as Scheme;
      contributions.getSchemeById.and.returnValue(of(funeralScheme));
      component.onSchemeIdChange('scheme-fun');

      expect(component.providerMode).toBe('OPTIONAL');
      expect(component.showProviderPicker()).toBeTrue();
      expect(component.isProviderRequired()).toBeFalse();
    });

    it('scheme lookup failure defaults to HEALTH so the form still renders', () => {
      // A brief blip in contributions-service must not strand the operator
      // on a blank screen — HEALTH is the safest fallback since it's the
      // largest catalogue.
      contributions.getSchemeById.and.returnValue(throwError(() => new Error('boom')));
      component.onSchemeIdChange('scheme-1');
      expect(component.activeLine).toBe('HEALTH');
      expect(component.usesItemLines).toBeTrue();
    });
  });

  // ──────────────────────────────────────────────────────────────────
  // Provider validation in submit
  // ──────────────────────────────────────────────────────────────────

  describe('submit — provider policy', () => {
    it('HEALTH without a provider is rejected — REQUIRED lines must have one on submit', () => {
      // V066: the service provider is captured regardless of who
      // receives payment. An operator that lacks the provider info
      // has to fetch it before the claim can be submitted; the payee
      // radio controls where the payout goes, not whether the provider
      // shows up on the record.
      contributions.getSchemeById.and.returnValue(of(healthScheme()));
      component.onSchemeIdChange('scheme-1');
      component.onBeneficiaryPicked({
        id: 'm-1', label: 'Sarah',
        beneficiary: { kind: 'MEMBER', memberId: 'm-1', dependantId: null },
      });
      component.lines[0].tariffCode = 'TC001';
      component.lines[0].unitPrice = '500';
      claims.submit.and.returnValue(of(envelope()));

      component.submit();
      expect(claims.submit).not.toHaveBeenCalled();
      expect(component.formError).toContain('require a service provider');
    });

    it('HEALTH with provider + payeeType MEMBER submits as reimbursement', () => {
      // The out-of-pocket reimbursement path — provider is captured
      // (so the adjudicator sees specialty, network, etc.) but the
      // payout goes back to the sponsoring member.
      contributions.getSchemeById.and.returnValue(of(healthScheme()));
      component.onSchemeIdChange('scheme-1');
      component.onBeneficiaryPicked({
        id: 'm-1', label: 'Sarah',
        beneficiary: { kind: 'MEMBER', memberId: 'm-1', dependantId: null },
      });
      component.providerId = 'p-42';
      component.payeeType = 'MEMBER';
      component.lines[0].tariffCode = 'TC001';
      component.lines[0].unitPrice = '500';
      claims.submit.and.returnValue(of(envelope()));

      component.submit();
      const payload = claims.submit.calls.mostRecent().args[0] as SubmitClaimPayload;
      expect(payload.providerId).toBe('p-42');
      expect(payload.payeeType).toBe('MEMBER');
      expect(component.formError).toBeNull();
    });

    it('LIFE claim submits with providerId=undefined and payeeType=MEMBER', () => {
      const lifeScheme = { ...healthScheme(), id: 'scheme-life', insuranceLine: 'LIFE', schemeType: 'term_life' } as Scheme;
      contributions.getSchemeById.and.returnValue(of(lifeScheme));
      component.onSchemeIdChange('scheme-life');
      component.onBeneficiaryPicked({
        id: 'm-1', label: 'Sarah',
        beneficiary: { kind: 'MEMBER', memberId: 'm-1', dependantId: null },
      });
      component.form.singleClaimedAmount = '50000';
      component.form.lifeCertificateRef = 'LIFE-CERT-2026-77';
      claims.submit.and.returnValue(of(envelope()));

      component.submit();

      expect(claims.submit).toHaveBeenCalled();
      const payload = claims.submit.calls.mostRecent().args[0] as SubmitClaimPayload;
      expect(payload.providerId)
        .withContext('LIFE claims are paid to the member — provider must not ride on the payload')
        .toBeUndefined();
      expect(payload.payeeType).toBe('MEMBER');
    });
  });

  // ──────────────────────────────────────────────────────────────────
  // Batching is opt-in
  // ──────────────────────────────────────────────────────────────────

  describe('submit payload — batching contract', () => {
    beforeEach(() => {
      contributions.getSchemeById.and.returnValue(of(healthScheme()));
      component.onSchemeIdChange('scheme-1');
      // Route through the beneficiary picker so downstream tests exercise
      // the same code path the UI actually uses.
      component.onBeneficiaryPicked({
        id: 'm-1', label: 'Sarah Nkomo', sublabel: 'MEM · MBR-000123',
        beneficiary: { kind: 'MEMBER', memberId: 'm-1', dependantId: null },
      });
      component.providerId = 'p-1';
      component.lines[0].tariffCode = 'TC001';
      component.lines[0].unitPrice = '500';
      component.lines[0].quantity  = 1;
      claims.submit.and.returnValue(of(envelope()));
    });

    it('omits batchNumber by default (opt-in, never auto-generated)', () => {
      // The whole point of the "make batching configurable" change: an
      // ad-hoc claim must not silently carry a batch tag. Regressing this
      // reintroduces the always-batched behaviour the operator asked us
      // to remove.
      component.submit();
      const payload = claims.submit.calls.mostRecent().args[0] as SubmitClaimPayload;
      expect(payload.batchNumber).toBeUndefined();
    });

    it('includes batchNumber when the toggle is on AND a tag is typed', () => {
      component.form.isBatched = true;
      component.form.batchNumber = 'BATCH-2026-07-A';
      component.submit();
      const payload = claims.submit.calls.mostRecent().args[0] as SubmitClaimPayload;
      expect(payload.batchNumber).toBe('BATCH-2026-07-A');
    });

    it('omits batchNumber when the toggle is on but the field is blank', () => {
      // Guards the empty-string edge case — a checked toggle with no tag
      // means the operator changed their mind. Sending "" would let the
      // backend persist an empty batch tag.
      component.form.isBatched = true;
      component.form.batchNumber = '   ';
      component.submit();
      const payload = claims.submit.calls.mostRecent().args[0] as SubmitClaimPayload;
      expect(payload.batchNumber).toBeUndefined();
    });
  });

  // ──────────────────────────────────────────────────────────────────
  // Beneficiary pick → automatic scheme resolution
  // ──────────────────────────────────────────────────────────────────
  //
  // The scheme picker was removed from the template — a member is
  // already enrolled on a scheme, so the form derives it from the
  // beneficiary automatically. Guard the auto-resolution paths so a
  // silent regression can't reintroduce a "why is the scheme blank?"
  // capture screen.

  describe('scheme auto-resolution', () => {
    it('picking a member fetches their scheme and snaps activeLine + currency', () => {
      members.getById.and.returnValue(of({ id: 'm-1', schemeId: 'scheme-1' } as Member));
      contributions.getSchemeById.and.returnValue(of(healthScheme()));

      component.onBeneficiaryPicked({
        id: 'm-1', label: 'Sarah',
        beneficiary: { kind: 'MEMBER', memberId: 'm-1', dependantId: null },
      });

      expect(component.schemeId).toBe('scheme-1');
      expect(component.schemeName).toBe('Health Basic');
      expect(component.schemeStatus).toBe('ok');
      expect(component.activeLine).toBe('HEALTH');
      expect(component.form.currencyCode).toBe('USD');
    });

    it('a dependant pick uses the sponsor member\'s scheme', () => {
      // Dependants share their sponsor's scheme. The picker routes
      // memberId → sponsor, so this just needs to prove we look up the
      // sponsor rather than the dependant when resolving the scheme.
      members.getById.and.callFake(id =>
        of({ id, schemeId: id === 'm-201' ? 'scheme-1' : null } as Member),
      );
      contributions.getSchemeById.and.returnValue(of(healthScheme()));

      component.onBeneficiaryPicked({
        id: 'dep-9', label: 'Sarah Zulu',
        beneficiary: {
          kind: 'DEPENDANT', memberId: 'm-201', dependantId: 'dep-9',
          sponsorName: 'Tapiwa Zulu',
        },
      });

      expect(members.getById).toHaveBeenCalledWith('m-201');
      expect(component.schemeStatus).toBe('ok');
    });

    it('a member without a scheme surfaces the "missing" state', () => {
      // Enrolment happens in a separate flow — a member captured but
      // not yet enrolled on a scheme can't have a claim submitted
      // against them. Surface that up front rather than 400ing at POST.
      members.getById.and.returnValue(of({ id: 'm-1', schemeId: null } as Member));

      component.onBeneficiaryPicked({
        id: 'm-1', label: 'Sarah',
        beneficiary: { kind: 'MEMBER', memberId: 'm-1', dependantId: null },
      });

      expect(component.schemeStatus).toBe('missing');
      expect(component.schemeId).toBeNull();
      expect(component.activeLine).toBeNull();
      // The scheme lookup must not have fired — no scheme to fetch.
      expect(contributions.getSchemeById).not.toHaveBeenCalled();
    });

    it('a scheme fetch error transitions to the "error" state without stranding the operator', () => {
      members.getById.and.returnValue(of({ id: 'm-1', schemeId: 'scheme-1' } as Member));
      contributions.getSchemeById.and.returnValue(throwError(() => new Error('boom')));

      component.onBeneficiaryPicked({
        id: 'm-1', label: 'Sarah',
        beneficiary: { kind: 'MEMBER', memberId: 'm-1', dependantId: null },
      });

      expect(component.schemeStatus).toBe('error');
      expect(component.schemeId).toBeNull();
      expect(component.activeLine).toBeNull();
    });

    it('clearing the beneficiary resets scheme state to idle', () => {
      members.getById.and.returnValue(of({ id: 'm-1', schemeId: 'scheme-1' } as Member));
      contributions.getSchemeById.and.returnValue(of(healthScheme()));

      component.onBeneficiaryPicked({
        id: 'm-1', label: 'Sarah',
        beneficiary: { kind: 'MEMBER', memberId: 'm-1', dependantId: null },
      });
      component.onBeneficiaryPicked(null);

      expect(component.schemeStatus).toBe('idle');
      expect(component.schemeId).toBeNull();
      expect(component.activeLine).toBeNull();
    });
  });

  // ──────────────────────────────────────────────────────────────────
  // Beneficiary picker → (memberId, dependantId) routing
  // ──────────────────────────────────────────────────────────────────

  describe('beneficiary picker', () => {
    it('picking a MEMBER hit sets memberId and clears dependantId', () => {
      component.onBeneficiaryPicked({
        id: 'm-1', label: 'Sarah Nkomo', sublabel: 'MEM · MBR-000123',
        beneficiary: { kind: 'MEMBER', memberId: 'm-1', dependantId: null },
      });
      expect(component.memberId).toBe('m-1');
      expect(component.dependantId).toBeNull();
      expect(component.beneficiaryId).toBe('m-1');
    });

    it('picking a DEPENDANT hit routes sponsor→memberId, dep→dependantId', () => {
      // The critical routing rule: the claim entity's memberId slot
      // holds the SPONSOR, and dependantId holds the picked dep. Getting
      // this wrong silently persists a claim keyed to the dep as if
      // they were their own member — the ledger consumer then can't
      // attribute the balance to the right household.
      component.onBeneficiaryPicked({
        id: 'dep-9', label: 'Sarah Zulu', sublabel: 'DEP · MBR-000201-02',
        beneficiary: {
          kind: 'DEPENDANT',
          memberId: 'm-201',
          dependantId: 'dep-9',
          sponsorName: 'Tapiwa Zulu',
          sponsorMemberNumber: 'MBR-000201',
        },
      });
      expect(component.memberId).toBe('m-201');
      expect(component.dependantId).toBe('dep-9');
      // The picker's own chip still shows the dep — beneficiaryId is
      // what the picker binds against, not memberId.
      expect(component.beneficiaryId).toBe('dep-9');
    });

    it('picking null clears both slots so a subsequent submit fails validation, not silently reuses the last member', () => {
      component.onBeneficiaryPicked({
        id: 'dep-9', label: 'Sarah Zulu',
        beneficiary: { kind: 'DEPENDANT', memberId: 'm-201', dependantId: 'dep-9' },
      });
      component.onBeneficiaryPicked(null);
      expect(component.memberId).toBeNull();
      expect(component.dependantId).toBeNull();
      expect(component.beneficiaryId).toBeNull();
    });

    it('submit payload for a dependant carries both memberId (sponsor) and dependantId', () => {
      contributions.getSchemeById.and.returnValue(of(healthScheme()));
      component.onSchemeIdChange('scheme-1');
      component.onBeneficiaryPicked({
        id: 'dep-9', label: 'Sarah Zulu',
        beneficiary: { kind: 'DEPENDANT', memberId: 'm-201', dependantId: 'dep-9' },
      });
      component.providerId = 'p-1';
      component.lines[0].tariffCode = 'TC001';
      component.lines[0].unitPrice = '500';
      claims.submit.and.returnValue(of(envelope()));

      component.submit();

      const payload = claims.submit.calls.mostRecent().args[0] as SubmitClaimPayload;
      expect(payload.memberId).toBe('m-201');
      expect(payload.dependantId).toBe('dep-9');
    });

    it('submit payload for a member omits dependantId', () => {
      contributions.getSchemeById.and.returnValue(of(healthScheme()));
      component.onSchemeIdChange('scheme-1');
      component.onBeneficiaryPicked({
        id: 'm-1', label: 'Sarah Nkomo',
        beneficiary: { kind: 'MEMBER', memberId: 'm-1', dependantId: null },
      });
      component.providerId = 'p-1';
      component.lines[0].tariffCode = 'TC001';
      component.lines[0].unitPrice = '500';
      claims.submit.and.returnValue(of(envelope()));

      component.submit();

      const payload = claims.submit.calls.mostRecent().args[0] as SubmitClaimPayload;
      expect(payload.memberId).toBe('m-1');
      expect(payload.dependantId).toBeUndefined();
    });
  });

  // ──────────────────────────────────────────────────────────────────
  // Envelope surfacing on the confirmation strip
  // ──────────────────────────────────────────────────────────────────

  describe('submit response → confirmation strip', () => {
    it('operator submission lands VERIFIED — no verification hop needed', () => {
      // Verification was removed on 2026-07-11: the operator vouches for
      // the capture, so the claim moves straight past the SUBMITTED gate.
      // Regressing this reintroduces a read-back-a-code UI the operator
      // has no way to satisfy.
      contributions.getSchemeById.and.returnValue(of(healthScheme()));
      component.onSchemeIdChange('scheme-1');
      component.onBeneficiaryPicked({
        id: 'm-1', label: 'Sarah', sublabel: 'MEM',
        beneficiary: { kind: 'MEMBER', memberId: 'm-1', dependantId: null },
      });
      component.providerId = 'p-1';
      component.lines[0].tariffCode = 'TC001';
      component.lines[0].unitPrice = '500';
      component.lines[0].quantity  = 1;

      claims.submit.and.returnValue(of(envelope()));
      component.submit();

      expect(component.submittedClaim?.status).toBe('VERIFIED');
      expect(component.submittedClaim?.claimNumber).toBe('CLM-000123');
      expect(toast.success).toHaveBeenCalledWith('Claim CLM-000123 submitted.');
    });
  });

  // ──────────────────────────────────────────────────────────────────
  // Client-side pre-flight validation catches shape errors before POST
  // ──────────────────────────────────────────────────────────────────

  describe('client-side validation', () => {
    it('VEHICLE claim without vehicleRegistration is blocked with a targeted error', () => {
      contributions.getSchemeById.and.returnValue(of(vehicleScheme()));
      component.onSchemeIdChange('scheme-2');
      component.onBeneficiaryPicked({
        id: 'm-1', label: 'Sarah', sublabel: 'MEM',
        beneficiary: { kind: 'MEMBER', memberId: 'm-1', dependantId: null },
      });
      component.providerId = 'p-1';
      component.form.singleClaimedAmount = '1200';
      // vehicleRegistration + incidentLocation intentionally left blank.

      component.submit();

      expect(component.formError).toContain('vehicle registration');
      expect(claims.submit).not.toHaveBeenCalled();
    });

  });
});
