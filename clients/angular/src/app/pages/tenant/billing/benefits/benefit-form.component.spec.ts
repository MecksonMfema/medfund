import { of } from 'rxjs';
import { ActivatedRoute, Router } from '@angular/router';
import { BenefitFormComponent } from './benefit-form.component';
import { ContributionsService } from '../../../../core/services/contributions.service';
import { BillingCatalogueService } from '../../../../core/services/billing-catalogue.service';
import { TariffCategoriesService } from '../../../../core/services/tariff-categories.service';

/**
 * V063 — the benefit form's Categories multi-select is required (at
 * least one covered category). Silent regression would break the
 * tariff → benefit resolution the adjudicator relies on: every claim
 * line would fall through to R03-TARIFF_UNMAPPED. Guards focus on
 * that validation branch + the payload/edit round-trip.
 */
class StubActivatedRoute {
  private schemeId: string | null = 'scheme-1';
  private benefitId: string | null = null;
  snapshot = {
    paramMap: {
      get: (key: string) => key === 'schemeId' ? this.schemeId
                        : key === 'id' ? this.benefitId : null,
    },
  };
  withBenefit(id: string | null): this { this.benefitId = id; return this; }
}

describe('BenefitFormComponent (V063 categories multi-select)', () => {
  let contributions: jasmine.SpyObj<ContributionsService>;
  let catalogue: jasmine.SpyObj<BillingCatalogueService>;
  let categoriesSvc: jasmine.SpyObj<TariffCategoriesService>;
  let router: jasmine.SpyObj<Router>;
  let route: StubActivatedRoute;
  let component: BenefitFormComponent;

  const scheme = { id: 'scheme-1', name: 'Gold', currencyCode: 'USD', insuranceLine: 'HEALTH' } as any;
  const benefitTypes = [{ id: 'bt-1', code: 'OUTPATIENT', label: 'Outpatient', sortOrder: 10, isActive: true }] as any;
  const categories = [
    { id: 'cat-1', code: 'CONSULTATION', label: 'Consultation', isCapOnly: false, isActive: true, sortOrder: 10 },
    { id: 'cat-2', code: 'PATHOLOGY',    label: 'Pathology',    isCapOnly: false, isActive: true, sortOrder: 20 },
  ];

  beforeEach(() => {
    contributions = jasmine.createSpyObj<ContributionsService>(
      'ContributionsService',
      ['getSchemeById', 'getBenefitById', 'createBenefit', 'updateBenefit'],
    );
    catalogue = jasmine.createSpyObj<BillingCatalogueService>(
      'BillingCatalogueService', ['listBenefitTypes'],
    );
    categoriesSvc = jasmine.createSpyObj<TariffCategoriesService>(
      'TariffCategoriesService', ['list'],
    );
    router = jasmine.createSpyObj<Router>('Router', ['navigate']);
    router.navigate.and.returnValue(Promise.resolve(true) as any);
    route = new StubActivatedRoute();

    contributions.getSchemeById.and.returnValue(of(scheme));
    catalogue.listBenefitTypes.and.returnValue(of(benefitTypes));
    categoriesSvc.list.and.returnValue(of(categories));
    contributions.createBenefit.and.returnValue(of({} as any));
    contributions.updateBenefit.and.returnValue(of({} as any));

    component = new BenefitFormComponent(
      contributions, catalogue, categoriesSvc,
      route as unknown as ActivatedRoute, router,
    );
  });

  it('submit_emptyCategoryIds_setsErrorAndSkipsService', () => {
    component.ngOnInit();
    component.form.name = 'Outpatient';
    component.form.benefitType = 'Outpatient';
    component.form.categoryIds = [];

    component.submit();

    expect(contributions.createBenefit).not.toHaveBeenCalled();
    expect(contributions.updateBenefit).not.toHaveBeenCalled();
    expect(component.errorMessage).toContain('at least one');
  });

  it('submit_valid_callsCreateWithCategoryIdsInPayload', () => {
    component.ngOnInit();
    component.form.name = 'Outpatient';
    component.form.benefitType = 'Outpatient';
    component.form.categoryIds = ['cat-1', 'cat-2'];

    component.submit();

    expect(contributions.createBenefit).toHaveBeenCalledWith(jasmine.objectContaining({
      schemeId: 'scheme-1',
      name: 'Outpatient',
      categoryIds: ['cat-1', 'cat-2'],
    }));
  });

  it('edit_prepopulatesCategoryIdsFromResponse', () => {
    route.withBenefit('benefit-1');
    contributions.getBenefitById.and.returnValue(of({
      id: 'benefit-1',
      schemeId: 'scheme-1',
      name: 'Outpatient',
      benefitType: 'Outpatient',
      currencyCode: 'USD',
      categoryIds: ['cat-1'],
    } as any));

    component.ngOnInit();

    expect(component.form.name).toBe('Outpatient');
    expect(component.form.categoryIds).toEqual(['cat-1']);
  });

  it('toggleCategory_addsAndRemoves', () => {
    component.ngOnInit();
    component.form.categoryIds = [];

    component.toggleCategory('cat-1');
    expect(component.form.categoryIds).toEqual(['cat-1']);
    expect(component.isCategorySelected('cat-1')).toBeTrue();

    component.toggleCategory('cat-2');
    expect(component.form.categoryIds).toEqual(['cat-1', 'cat-2']);

    // Toggling an already-selected category removes it.
    component.toggleCategory('cat-1');
    expect(component.form.categoryIds).toEqual(['cat-2']);
    expect(component.isCategorySelected('cat-1')).toBeFalse();
  });
});
