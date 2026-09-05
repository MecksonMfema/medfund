import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { environment } from '../../../environments/environment';
import { TenantReportScheduleService } from './tenant-report-schedule.service';

describe('TenantReportScheduleService', () => {
  const baseUrl = environment.apiBaseUrl;
  const tenantId = '11111111-2222-3333-4444-555555555555';
  const scheduleId = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee';

  let service: TenantReportScheduleService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(TenantReportScheduleService);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  it('list() GETs /tenants/{tenantId}/report-schedules', () => {
    service.list(tenantId).subscribe();
    const req = http.expectOne(`${baseUrl}/tenants/${tenantId}/report-schedules`);
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('create() POSTs the body', () => {
    const body = {
      reportKey: 'COMMISSION_STATEMENT',
      enabled: true,
      cadence: 'MONTHLY' as const,
      hourOfDay: 8,
      dayOfMonth: 1,
    };
    service.create(tenantId, body).subscribe();
    const req = http.expectOne(`${baseUrl}/tenants/${tenantId}/report-schedules`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(body);
    req.flush({});
  });

  it('update() PUTs the patch body', () => {
    service.update(tenantId, scheduleId, { enabled: false }).subscribe();
    const req = http.expectOne(`${baseUrl}/tenants/${tenantId}/report-schedules/${scheduleId}`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ enabled: false });
    req.flush({});
  });

  it('delete() DELETEs the schedule', () => {
    service.delete(tenantId, scheduleId).subscribe();
    const req = http.expectOne(`${baseUrl}/tenants/${tenantId}/report-schedules/${scheduleId}`);
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });

  it('addRecipient() POSTs to the nested resource', () => {
    service.addRecipient(tenantId, scheduleId, { email: 'a@b.com' }).subscribe();
    const req = http.expectOne(
      `${baseUrl}/tenants/${tenantId}/report-schedules/${scheduleId}/recipients`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ email: 'a@b.com' });
    req.flush({});
  });

  it('deleteRecipient() DELETEs the nested resource', () => {
    const recipientId = 'ffffffff-1111-2222-3333-444444444444';
    service.deleteRecipient(tenantId, scheduleId, recipientId).subscribe();
    const req = http.expectOne(
      `${baseUrl}/tenants/${tenantId}/report-schedules/${scheduleId}/recipients/${recipientId}`);
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });

  it('runHistory() GETs finance schedule runs with a limit param', () => {
    service.runHistory(scheduleId, 42).subscribe();
    const req = http.expectOne(
      r => r.url === `${baseUrl}/reports/scheduled/schedules/${scheduleId}/runs`
        && r.params.get('limit') === '42');
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('rerun() POSTs to the finance rerun endpoint', () => {
    const jobId = 'cccccccc-dddd-eeee-ffff-000000000000';
    service.rerun(jobId).subscribe();
    const req = http.expectOne(`${baseUrl}/reports/scheduled/${jobId}/rerun`);
    expect(req.request.method).toBe('POST');
    req.flush({ jobId, status: 'requested' });
  });

  it('downloadRun() GETs a blob from the in-app download endpoint', () => {
    const jobId = 'cccccccc-dddd-eeee-ffff-000000000000';
    service.downloadRun(jobId).subscribe();
    const req = http.expectOne(`${baseUrl}/reports/scheduled/runs/${jobId}/download`);
    expect(req.request.method).toBe('GET');
    expect(req.request.responseType).toBe('blob');
    req.flush(new Blob(['x']));
  });
});
