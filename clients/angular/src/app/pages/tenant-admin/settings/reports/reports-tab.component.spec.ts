import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { Router } from '@angular/router';
import { of } from 'rxjs';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';

import { TenantReportsTabComponent } from './reports-tab.component';
import {
  TenantReportConfigRow,
  TenantReportConfigService,
} from '../../../../core/services/tenant-report-config.service';
import { TenantService, Tenant } from '../../../../core/services/tenant.service';
import { ConfirmService } from '../../../../shared/components/confirm-dialog/confirm.service';
import { provideMockPermissions } from '../../../../_test-utils/mock-permission.service';

describe('TenantReportsTabComponent', () => {
  const tenantId = 't1';

  let fixture: ComponentFixture<TenantReportsTabComponent>;
  let component: TenantReportsTabComponent;
  let configSpy: jasmine.SpyObj<TenantReportConfigService>;
  let confirmSpy: jasmine.SpyObj<ConfirmService>;
  let routerSpy: jasmine.SpyObj<Router>;

  const cadencedRow: TenantReportConfigRow = {
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
    activeScheduleCount: 2,
  };

  const cadencedRowNoSchedules: TenantReportConfigRow = {
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
    activeScheduleCount: 0,
  };

  const nonCadencedRow: TenantReportConfigRow = {
    id: 'c3',
    tenantId,
    reportKey: 'AGE_MIX',
    label: 'Age mix',
    family: 'MEMBERS',
    familyLabel: 'Members',
    enabled: true,
    cadenced: false,
    updatedAt: null,
    updatedBy: null,
  };

  beforeEach(async () => {
    configSpy = jasmine.createSpyObj('TenantReportConfigService', ['list', 'bulkUpsert', 'invalidate']);
    confirmSpy = jasmine.createSpyObj('ConfirmService', ['ask']);
    routerSpy = jasmine.createSpyObj('Router', ['navigate']);

    configSpy.list.and.returnValue(of([cadencedRow, cadencedRowNoSchedules, nonCadencedRow]));
    configSpy.bulkUpsert.and.returnValue(of([]));

    const stubTenant: Tenant = {
      id: tenantId, name: 'T', slug: 't', status: 'ACTIVE',
      timezone: 'UTC', insuranceLines: ['HEALTH'], providerRegLabel: 'Doctor',
    };
    const tenantServiceStub: Partial<TenantService> = {
      getTenant: () => stubTenant,
      getTenantId: () => tenantId,
    };

    await TestBed.configureTestingModule({
      imports: [TenantReportsTabComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideMockPermissions([], { superAdmin: true }),
        { provide: TenantReportConfigService, useValue: configSpy },
        { provide: TenantService,             useValue: tenantServiceStub },
        { provide: ConfirmService,            useValue: confirmSpy },
        { provide: Router,                    useValue: routerSpy },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(TenantReportsTabComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('manageSchedule navigates to the schedules page with the reportKey fragment', () => {
    component.manageSchedule(cadencedRow);
    expect(routerSpy.navigate).toHaveBeenCalledWith(
      ['/tenant/admin/settings/report-schedules'],
      { fragment: 'COMMISSION_STATEMENT' },
    );
  });

  it('save with a disabling row that has schedules asks for confirmation before PUT', fakeAsync(() => {
    confirmSpy.ask.and.returnValue(Promise.resolve(true));
    component.toggle(cadencedRow);
    component.save();
    expect(confirmSpy.ask).toHaveBeenCalled();
    const call = confirmSpy.ask.calls.mostRecent().args[0];
    expect(call.title).toBe('Pause scheduled deliveries?');
    expect(call.message).toContain('2 scheduled schedules');
    tick();
    expect(configSpy.bulkUpsert).toHaveBeenCalledWith(
      tenantId, [{ reportKey: 'COMMISSION_STATEMENT', enabled: false }]);
  }));

  it('save cancels when the user declines the cascade-disable modal', fakeAsync(() => {
    confirmSpy.ask.and.returnValue(Promise.resolve(false));
    component.toggle(cadencedRow);
    component.save();
    tick();
    expect(configSpy.bulkUpsert).not.toHaveBeenCalled();
  }));

  it('save skips the modal when disabling rows have no active schedules', () => {
    component.toggle(cadencedRowNoSchedules);
    component.save();
    expect(confirmSpy.ask).not.toHaveBeenCalled();
    expect(configSpy.bulkUpsert).toHaveBeenCalledWith(
      tenantId, [{ reportKey: 'LOSS_RATIO', enabled: false }]);
  });

  it('save skips the modal when only enabling rows', () => {
    component.currentEnabled.set('COMMISSION_STATEMENT', false);
    // reset original to make it appear "was disabled, now enabling"
    (component as unknown as { originalEnabled: Map<string, boolean> })
      .originalEnabled.set('COMMISSION_STATEMENT', false);
    component.toggle(cadencedRow);
    component.save();
    expect(confirmSpy.ask).not.toHaveBeenCalled();
    expect(configSpy.bulkUpsert).toHaveBeenCalled();
  });
});
