import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { Ifrs17PortfolioService } from './ifrs17-portfolio.service';
import { environment } from '../../../environments/environment';

/**
 * Wire-shape guard for {@link Ifrs17PortfolioService}. Backend at
 * {@code /api/v1/underwriting/portfolios}; the gateway proxies the prefix
 * to user-service. A rename of any URL would silently break the tenant-admin
 * underwriting page, so the five seams are asserted directly.
 */
describe('Ifrs17PortfolioService', () => {
  let service: Ifrs17PortfolioService;
  let http: HttpTestingController;
  const baseUrl = environment.apiBaseUrl;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(Ifrs17PortfolioService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('GET /underwriting/portfolios with includeInactive flag', () => {
    service.list(true).subscribe();
    const req = http.expectOne(r =>
      r.url === `${baseUrl}/underwriting/portfolios`
      && r.params.get('includeInactive') === 'true');
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('GET /underwriting/portfolios/search with q + limit', () => {
    service.search('motor', 5).subscribe();
    const req = http.expectOne(r =>
      r.url === `${baseUrl}/underwriting/portfolios/search`
      && r.params.get('q') === 'motor'
      && r.params.get('limit') === '5');
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('POST /underwriting/portfolios with body', () => {
    service.create({ name: 'Motor 2026', description: null, insuranceLine: 'VEHICLE' }).subscribe();
    const req = http.expectOne(`${baseUrl}/underwriting/portfolios`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body.name).toBe('Motor 2026');
    expect(req.request.body.insuranceLine).toBe('VEHICLE');
    req.flush({ id: 'p1', name: 'Motor 2026', isActive: true });
  });

  it('PUT /underwriting/portfolios/{id}', () => {
    service.update('p1', { name: 'New Name', description: null, insuranceLine: null }).subscribe();
    const req = http.expectOne(`${baseUrl}/underwriting/portfolios/p1`);
    expect(req.request.method).toBe('PUT');
    req.flush({ id: 'p1', name: 'New Name', isActive: true });
  });

  it('DELETE /underwriting/portfolios/{id}', () => {
    service.delete('p1').subscribe();
    const req = http.expectOne(`${baseUrl}/underwriting/portfolios/p1`);
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });
});
