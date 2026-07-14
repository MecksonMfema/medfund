import { ActivatedRoute, Router } from '@angular/router';
import { ClaimDetailComponent } from './claim-detail.component';
import { ClaimsService } from '../../../../core/services/claims.service';
import { ClaimsConfigService } from '../../../../core/services/claims-config.service';
import {
  AnnualCapUtilization,
  BeneficiaryBenefitUtilization,
  ContributionsService,
} from '../../../../core/services/contributions.service';
import { MembersService } from '../../../../core/services/members.service';
import { ProvidersService } from '../../../../core/services/providers.service';
import { PreAuthService } from '../../../../core/services/pre-auth.service';
import { ConfirmService } from '../../../../shared/components/confirm-dialog/confirm.service';
import { ToastService } from '../../../../shared/components/toast/toast.service';
import { PermissionService } from '../../../../core/security/permission.service';

/**
 * V062 — the claim-detail page renders a scheme-level "Annual cap"
 * progress row above the per-benefit rows when the scheme has a cap.
 * The three getters/helpers under test (showAnnualCap, annualCapPct,
 * visibleUtilization) are the gates: silently breaking them would
 * either hide the cap row when it should show or expose the panel to
 * NO_TRACKING benefits that don't belong there.
 */
describe('ClaimDetailComponent (V062 utilization gates)', () => {
  let component: ClaimDetailComponent;

  function makeComponent(): ClaimDetailComponent {
    const permissions = jasmine.createSpyObj<PermissionService>('PermissionService', ['has']);
    permissions.has.and.returnValue(false);
    return new ClaimDetailComponent(
      {} as ClaimsService,
      {} as ClaimsConfigService,
      {} as ContributionsService,
      {} as MembersService,
      {} as ProvidersService,
      {} as PreAuthService,
      {} as ConfirmService,
      {} as ToastService,
      permissions,
      { snapshot: { paramMap: { get: () => null } } } as unknown as ActivatedRoute,
      {} as Router,
    );
  }

  beforeEach(() => { component = makeComponent(); });

  // ── showAnnualCap ────────────────────────────────────────────────────

  it('showAnnualCap_falseWhenAnnualCapMissing', () => {
    component.annualCap = null;
    expect(component.showAnnualCap).toBeFalse();
  });

  it('showAnnualCap_falseWhenCapAmountNull', () => {
    component.annualCap = {
      schemeId: 's', memberId: 'm', policyYear: 2026,
      consumedAmount: 0, capAmount: null, currencyCode: 'USD',
    } as AnnualCapUtilization;
    expect(component.showAnnualCap).toBeFalse();
  });

  it('showAnnualCap_trueWhenCapAmountPresent', () => {
    component.annualCap = {
      schemeId: 's', memberId: 'm', policyYear: 2026,
      consumedAmount: 100, capAmount: 5000, currencyCode: 'USD',
    } as AnnualCapUtilization;
    expect(component.showAnnualCap).toBeTrue();
  });

  // ── annualCapPct ─────────────────────────────────────────────────────

  it('annualCapPct_nullWhenNoCap', () => {
    component.annualCap = null;
    expect(component.annualCapPct()).toBeNull();
  });

  it('annualCapPct_returnsRoundedPercent', () => {
    component.annualCap = {
      schemeId: 's', memberId: 'm', policyYear: 2026,
      consumedAmount: 1500, capAmount: 5000, currencyCode: 'USD',
    } as AnnualCapUtilization;
    // 1500 / 5000 = 30%
    expect(component.annualCapPct()).toBe(30);
  });

  it('annualCapPct_capsAt100WhenOverConsumed', () => {
    component.annualCap = {
      schemeId: 's', memberId: 'm', policyYear: 2026,
      consumedAmount: 6000, capAmount: 5000, currencyCode: 'USD',
    } as AnnualCapUtilization;
    expect(component.annualCapPct()).toBe(100);
  });

  // ── visibleUtilization ──────────────────────────────────────────────

  it('visibleUtilization_filtersNoTracking', () => {
    component.utilization = [
      { id: '1', memberId: 'm', benefitId: 'b1', usageMode: 'RUNNING_BALANCE' } as BeneficiaryBenefitUtilization,
      { id: '2', memberId: 'm', benefitId: 'b2', usageMode: 'NO_TRACKING' } as BeneficiaryBenefitUtilization,
      { id: '3', memberId: 'm', benefitId: 'b3', usageMode: 'ONE_TIME_PER_BENEFICIARY' } as BeneficiaryBenefitUtilization,
    ];
    const visible = component.visibleUtilization;
    expect(visible.length).toBe(2);
    expect(visible.map(r => r.id)).toEqual(['1', '3']);
  });
});
