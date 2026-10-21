import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { ApiService } from './api.service';
import { environment } from '../../../environments/environment';
import {
  TenantIfrs17NotificationConfigService,
  TenantIfrs17NotificationConfigRow,
} from './tenant-ifrs17-notification-config.service';

describe('TenantIfrs17NotificationConfigService', () => {
  let service: TenantIfrs17NotificationConfigService;
  let httpMock: HttpTestingController;
  const tenantId = '00000000-0000-4000-8000-000000000001';
  const rowId = '11111111-1111-4000-8000-000000000001';
  const base = `${environment.apiBaseUrl}/tenants/${tenantId}/ifrs17-notification-config`;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [ApiService, TenantIfrs17NotificationConfigService],
    });
    service = TestBed.inject(TenantIfrs17NotificationConfigService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('lists rows via GET', () => {
    const expected: TenantIfrs17NotificationConfigRow[] = [{
      id: rowId, tenantId,
      eventType: 'ONEROUS_TRANSITION', deliveryMethod: 'EMAIL',
      recipient: 'risk@medfund.example', throttleMinutes: 15, isActive: true,
      updatedAt: null, updatedByEmail: null,
    }];
    service.list(tenantId).subscribe(rows => expect(rows).toEqual(expected));
    const req = httpMock.expectOne(base);
    expect(req.request.method).toBe('GET');
    req.flush(expected);
  });

  it('adds a row via POST', () => {
    service.add(tenantId, {
      eventType: 'CSM_NEGATIVE', deliveryMethod: 'BOTH',
      recipient: 'ops@medfund.example', throttleMinutes: 30, isActive: true,
    }).subscribe();
    const req = httpMock.expectOne(base);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      eventType: 'CSM_NEGATIVE', deliveryMethod: 'BOTH',
      recipient: 'ops@medfund.example', throttleMinutes: 30, isActive: true,
    });
    req.flush({});
  });

  it('updates a row via PUT', () => {
    service.update(tenantId, rowId, {
      deliveryMethod: 'WEBHOOK', throttleMinutes: 60, isActive: false,
    }).subscribe();
    const req = httpMock.expectOne(`${base}/${rowId}`);
    expect(req.request.method).toBe('PUT');
    req.flush({});
  });

  it('deletes a row via DELETE', () => {
    service.delete(tenantId, rowId).subscribe();
    const req = httpMock.expectOne(`${base}/${rowId}`);
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });
});
