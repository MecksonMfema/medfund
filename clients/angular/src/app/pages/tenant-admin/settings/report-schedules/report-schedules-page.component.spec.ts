import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { ActivatedRoute } from '@angular/router';
import { of, Subject } from 'rxjs';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';

import { ReportSchedulesPageComponent } from './report-schedules-page.component';
import { TenantReportScheduleService, TenantReportScheduleRow } from '../../../../core/services/tenant-report-schedule.service';
import { TenantReportConfigService, TenantReportConfigRow } from '../../../../core/services/tenant-report-config.service';
import { TenantService, Tenant } from '../../../../core/services/tenant.service';
import { ToastService } from '../../../../shared/components/toast/toast.service';
import { ConfirmService } from '../../../../shared/components/confirm-dialog/confirm.service';

describe('ReportSchedulesPageComponent', () => {
  const tenantId = 't1';

  let fixture: ComponentFixture<ReportSchedulesPageComponent>;
  let component: ReportSchedulesPageComponent;
  let scheduleServiceSpy: jasmine.SpyObj<TenantReportScheduleService>;
  let configServiceSpy: jasmine.SpyObj<TenantReportConfigService>;
  let tenantServiceStub: Partial<TenantService>;
  let toastSpy: jasmine.SpyObj<ToastService>;
  let confirmSpy: jasmine.SpyObj<ConfirmService>;

  const scheduleRow: TenantReportScheduleRow = {
    id: 's1',
    tenantId,
    reportKey: 'COMMISSION_STATEMENT',
    reportLabel: 'Commission statement',
    enabled: true,
    cadence: 'MONTHLY',
    hourOfDay: 8,
    dayOfWeek: null,
    dayOfMonth: 1,
    reportingCurrency: null,
    lastFiredAt: null,
    lastStatus: null,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    recipients: [
      {
        id: 'r1', scheduleId: 's1', email: 'a@b.com', displayName: null,
        isActive: true, unsubscribeToken: 'tok', createdAt: '', updatedAt: '',
      },
    ],
  };

  const catalogRow: TenantReportConfigRow = {
    id: 'c1',
    tenantId,
    reportKey: 'COMMISSION_STATEMENT',
    label: 'Commission statement',
    family: 'COMMISSION',
    familyLabel: 'Commission',
    enabled: true,
    cadenced: true,
    updatedAt: null,
    updatedBy: null,
  };

  const eligibleUnscheduled: TenantReportConfigRow = {
    id: 'c2',
    tenantId,
    reportKey: 'LOSS_RATIO',
    label: 'Loss ratio',
    family: 'RECONCILIATION',
    familyLabel: 'Reconciliation',
    enabled: true,
    cadenced: true,
    updatedAt: null,
    updatedBy: null,
  };

  beforeEach(async () => {
    scheduleServiceSpy = jasmine.createSpyObj('TenantReportScheduleService', [
      'list', 'create', 'update', 'delete',
      'addRecipient', 'updateRecipient', 'deleteRecipient',
      'runHistory', 'rerun', 'downloadRun', 'get',
    ]);
    configServiceSpy = jasmine.createSpyObj('TenantReportConfigService',
      ['list', 'invalidate']);
    toastSpy = jasmine.createSpyObj('ToastService',
      ['success', 'error', 'warning', 'info']);
    confirmSpy = jasmine.createSpyObj('ConfirmService', ['ask']);

    scheduleServiceSpy.list.and.returnValue(of([scheduleRow]));
    scheduleServiceSpy.update.and.returnValue(of(scheduleRow));
    scheduleServiceSpy.delete.and.returnValue(of(void 0));
    scheduleServiceSpy.create.and.returnValue(of(scheduleRow));
    scheduleServiceSpy.runHistory.and.returnValue(of([]));
    configServiceSpy.list.and.returnValue(of([catalogRow, eligibleUnscheduled]));

    const stubTenant: Tenant = {
      id: tenantId, name: 'T', slug: 't', status: 'ACTIVE',
      timezone: 'UTC', insuranceLines: ['HEALTH'], providerRegLabel: 'Doctor',
    };
    tenantServiceStub = {
      getTenant: () => stubTenant,
      getTenantId: () => tenantId,
    };

    await TestBed.configureTestingModule({
      imports: [ReportSchedulesPageComponent, HttpClientTestingModule],
      providers: [
        { provide: TenantReportScheduleService, useValue: scheduleServiceSpy },
        { provide: TenantReportConfigService,   useValue: configServiceSpy },
        { provide: TenantService,               useValue: tenantServiceStub },
        { provide: ToastService,                useValue: toastSpy },
        { provide: ConfirmService,              useValue: confirmSpy },
        { provide: ActivatedRoute,              useValue: { fragment: new Subject<string>() } },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(ReportSchedulesPageComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('groups existing schedules by family and lists unscheduled eligible keys', () => {
    expect(component.groups.length).toBe(1);
    expect(component.groups[0].family).toBe('COMMISSION');
    expect(component.groups[0].cards[0].row.reportKey).toBe('COMMISSION_STATEMENT');
    expect(component.eligibleCandidates.map(c => c.reportKey)).toEqual(['LOSS_RATIO']);
  });

  it('saveCard PUTs the updated schedule', () => {
    const card = component.groups[0].cards[0];
    card.row.enabled = false;
    component.saveCard(card);
    expect(scheduleServiceSpy.update).toHaveBeenCalledWith(
      tenantId, 's1', jasmine.objectContaining({ enabled: false }));
  });

  it('saveCard rejects invalid cadence/day combos client-side', () => {
    const card = component.groups[0].cards[0];
    card.row.cadence = 'WEEKLY';
    card.row.dayOfWeek = null;
    component.saveCard(card);
    expect(scheduleServiceSpy.update).not.toHaveBeenCalled();
    expect(toastSpy.error).toHaveBeenCalled();
  });

  it('deleteCard confirms then DELETEs', fakeAsync(() => {
    confirmSpy.ask.and.returnValue(Promise.resolve(true));
    component.deleteCard(component.groups[0].cards[0]);
    tick();
    expect(scheduleServiceSpy.delete).toHaveBeenCalledWith(tenantId, 's1');
  }));

  it('submitCreate POSTs a new schedule for the picked candidate', () => {
    component.openCreate({
      reportKey: 'LOSS_RATIO', label: 'Loss ratio',
      family: 'RECONCILIATION', familyLabel: 'Reconciliation',
    });
    component.createDraft.cadence = 'QUARTERLY';
    component.createDraft.hourOfDay = 6;
    component.submitCreate();
    expect(scheduleServiceSpy.create).toHaveBeenCalledWith(
      tenantId, jasmine.objectContaining({
        reportKey: 'LOSS_RATIO', cadence: 'QUARTERLY', hourOfDay: 6,
      }));
  });

  it('toggleEnabled warns when the report is disabled at the config layer', () => {
    const card = component.groups[0].cards[0];
    card.configEnabled = false;
    card.row.enabled = true;
    component.toggleEnabled(card);
    expect(toastSpy.warning).toHaveBeenCalled();
  });
});
