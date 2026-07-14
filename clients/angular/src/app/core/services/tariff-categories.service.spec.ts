import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { TariffCategoriesService, TariffCategory } from './tariff-categories.service';
import { environment } from '../../../environments/environment';

/**
 * V063 wire-shape guard for TariffCategoriesService. Silent URL rename
 * would leave the tenant-admin catalogue page and the tariff/benefit
 * forms rendering empty dropdowns without an obvious error. Guard the
 * URL + method + payload of every method.
 */
describe('TariffCategoriesService (V063 catalogue)', () => {
  let service: TariffCategoriesService;
  let http: HttpTestingController;

  const baseUrl = environment.apiBaseUrl;
  const sampleCategory: TariffCategory = {
    id: 'cat-1', code: 'CONSULTATION', label: 'Consultation',
    isCapOnly: false, isActive: true, sortOrder: 10,
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(TariffCategoriesService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('list_getsFromCorrectEndpointWithActiveOnlyQueryParam', () => {
    service.list(true).subscribe(rows => {
      expect(rows).toEqual([sampleCategory]);
    });
    // activeOnly=true rides as a query string per ApiService.get convention.
    const req = http.expectOne(r =>
      r.url === `${baseUrl}/tariff-categories` && r.params.get('activeOnly') === 'true');
    expect(req.request.method).toBe('GET');
    req.flush([sampleCategory]);
  });

  it('create_postsPayload', () => {
    const payload = { code: 'PHYSIO', label: 'Physiotherapy', isCapOnly: false };
    service.create(payload).subscribe(row => {
      expect(row.code).toBe('CONSULTATION');
    });
    const req = http.expectOne(`${baseUrl}/tariff-categories`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(payload);
    req.flush(sampleCategory);
  });

  it('update_putsWithId', () => {
    const payload = { code: 'CONSULTATION', label: 'Consultation (updated)' };
    service.update('cat-1', payload).subscribe();
    const req = http.expectOne(`${baseUrl}/tariff-categories/cat-1`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual(payload);
    req.flush({ ...sampleCategory, label: 'Consultation (updated)' });
  });

  it('deactivate_deletesWithId', () => {
    service.deactivate('cat-1').subscribe();
    const req = http.expectOne(`${baseUrl}/tariff-categories/cat-1`);
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });
});
