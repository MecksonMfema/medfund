import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { UnitLinkedFundService } from './unit-linked-fund.service';
import { environment } from '../../../environments/environment';

/**
 * Wire-shape guard for Phase 15 §8 (I4) admin endpoints under
 * {@code /api/v1/underwriting/*}.
 */
describe('UnitLinkedFundService', () => {
  let service: UnitLinkedFundService;
  let http: HttpTestingController;
  const baseUrl = environment.apiBaseUrl;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(UnitLinkedFundService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('GET /underwriting/funds', () => {
    service.listFunds().subscribe();
    const req = http.expectOne(`${baseUrl}/underwriting/funds`);
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('GET /underwriting/funds/active', () => {
    service.listActiveFunds().subscribe();
    const req = http.expectOne(`${baseUrl}/underwriting/funds/active`);
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('POST /underwriting/funds', () => {
    service.addFund({ name: 'F1', currency: 'USD', baseAssetClass: 'MULTI_ASSET' }).subscribe();
    const req = http.expectOne(`${baseUrl}/underwriting/funds`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ name: 'F1', currency: 'USD', baseAssetClass: 'MULTI_ASSET' });
    req.flush({});
  });

  it('PUT /underwriting/funds/{id}', () => {
    service.updateFund('f1', { name: 'F1v2', baseAssetClass: 'EQUITY', isActive: false }).subscribe();
    const req = http.expectOne(`${baseUrl}/underwriting/funds/f1`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ name: 'F1v2', baseAssetClass: 'EQUITY', isActive: false });
    req.flush({});
  });

  it('DELETE /underwriting/funds/{id}', () => {
    service.deleteFund('f1').subscribe();
    const req = http.expectOne(`${baseUrl}/underwriting/funds/f1`);
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });

  it('GET /underwriting/funds/{id}/nav-history', () => {
    service.listNavHistory('f1').subscribe();
    const req = http.expectOne(`${baseUrl}/underwriting/funds/f1/nav-history`);
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('POST /underwriting/funds/{id}/nav-history', () => {
    service.addNavRow('f1', { valuationDate: '2026-08-27', navPerUnit: '1.2340' }).subscribe();
    const req = http.expectOne(`${baseUrl}/underwriting/funds/f1/nav-history`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ valuationDate: '2026-08-27', navPerUnit: '1.2340' });
    req.flush({});
  });

  it('GET /underwriting/funds/{id}/variable-fees', () => {
    service.listVariableFees('f1').subscribe();
    const req = http.expectOne(`${baseUrl}/underwriting/funds/f1/variable-fees`);
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('POST /underwriting/funds/{id}/variable-fees', () => {
    service.addVariableFee('f1', {
      effectiveFrom: '2026-01-01', effectiveTo: '2027-01-01', feePercentage: '0.0150',
    }).subscribe();
    const req = http.expectOne(`${baseUrl}/underwriting/funds/f1/variable-fees`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      effectiveFrom: '2026-01-01', effectiveTo: '2027-01-01', feePercentage: '0.0150',
    });
    req.flush({});
  });

  it('PUT /underwriting/variable-fees/{id}', () => {
    service.updateVariableFee('vf1', { effectiveTo: null, feePercentage: '0.0175' }).subscribe();
    const req = http.expectOne(`${baseUrl}/underwriting/variable-fees/vf1`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ effectiveTo: null, feePercentage: '0.0175' });
    req.flush({});
  });

  it('DELETE /underwriting/variable-fees/{id}', () => {
    service.deleteVariableFee('vf1').subscribe();
    const req = http.expectOne(`${baseUrl}/underwriting/variable-fees/vf1`);
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });

  it('GET /underwriting/funds/{id}/ledger', () => {
    service.listLedgerByFund('f1').subscribe();
    const req = http.expectOne(`${baseUrl}/underwriting/funds/f1/ledger`);
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('GET /underwriting/policies/{id}/ledger', () => {
    service.listLedgerByPolicy('p1').subscribe();
    const req = http.expectOne(`${baseUrl}/underwriting/policies/p1/ledger`);
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('POST /underwriting/funds/{id}/ledger', () => {
    service.appendLedger('f1', {
      policyId: 'p1',
      transactionDate: '2026-08-15',
      transactionType: 'PURCHASE',
      units: '1000.000000',
      price: '1.2340',
    }).subscribe();
    const req = http.expectOne(`${baseUrl}/underwriting/funds/f1/ledger`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      policyId: 'p1',
      transactionDate: '2026-08-15',
      transactionType: 'PURCHASE',
      units: '1000.000000',
      price: '1.2340',
    });
    req.flush({});
  });
});
