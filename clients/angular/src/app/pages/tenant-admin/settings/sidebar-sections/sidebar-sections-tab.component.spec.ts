import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';

import { SidebarSectionsTabComponent } from './sidebar-sections-tab.component';
import {
  TenantSidebarConfigService,
  TenantSidebarSectionConfigRow,
} from '../../../../core/services/tenant-sidebar-config.service';
import { TenantService, Tenant } from '../../../../core/services/tenant.service';
import { provideMockPermissions } from '../../../../_test-utils/mock-permission.service';

describe('SidebarSectionsTabComponent', () => {
  const tenantId = 't1';

  let fixture: ComponentFixture<SidebarSectionsTabComponent>;
  let component: SidebarSectionsTabComponent;
  let configSpy: jasmine.SpyObj<TenantSidebarConfigService>;
  let setTenantSpy: jasmine.Spy;

  const rowA: TenantSidebarSectionConfigRow = {
    id: null, tenantId, sectionKey: 'FINANCE_PAYMENT_RUNS',
    label: 'Payment Runs', group: 'FINANCE', groupLabel: 'Finance',
    enabled: true, updatedAt: null, updatedBy: null,
  };
  const rowB: TenantSidebarSectionConfigRow = {
    id: null, tenantId, sectionKey: 'BILLING_TRANSACTIONS',
    label: 'Transactions', group: 'BILLING', groupLabel: 'Billing',
    enabled: true, updatedAt: null, updatedBy: null,
  };

  beforeEach(async () => {
    configSpy = jasmine.createSpyObj('TenantSidebarConfigService', ['list', 'bulkUpsert', 'invalidate']);
    setTenantSpy = jasmine.createSpy('setTenant');

    configSpy.list.and.returnValue(of([rowA, rowB]));
    configSpy.bulkUpsert.and.returnValue(of([]));

    const stubTenant: Tenant = {
      id: tenantId, name: 'T', slug: 't', status: 'ACTIVE',
      timezone: 'UTC', insuranceLines: ['HEALTH'], providerRegLabel: 'Doctor',
    };
    const tenantServiceStub: Partial<TenantService> = {
      getTenant: () => stubTenant,
      getTenantId: () => tenantId,
      setTenant: setTenantSpy,
    };

    await TestBed.configureTestingModule({
      imports: [SidebarSectionsTabComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideMockPermissions([], { superAdmin: true }),
        { provide: TenantSidebarConfigService, useValue: configSpy },
        { provide: TenantService,              useValue: tenantServiceStub },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(SidebarSectionsTabComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('loads rows on init grouped by nav-group', () => {
    expect(configSpy.list).toHaveBeenCalledWith(tenantId);
    expect(component.buckets.length).toBe(2);
    expect(component.buckets.map(b => b.groupLabel)).toContain('Finance');
    expect(component.buckets.map(b => b.groupLabel)).toContain('Billing');
  });

  it('toggle flips the current state and marks the tab dirty', () => {
    expect(component.dirty()).toBeFalse();
    component.toggle(rowA);
    expect(component.dirty()).toBeTrue();
    expect(component.isEnabled(rowA)).toBeFalse();
  });

  it('save sends only dirty entries', () => {
    component.toggle(rowA);
    component.save();
    expect(configSpy.bulkUpsert).toHaveBeenCalledWith(
      tenantId, [{ sectionKey: 'FINANCE_PAYMENT_RUNS', enabled: false }]);
  });

  it('save re-emits the current tenant so the sidebar rebuilds without a reload', () => {
    component.toggle(rowA);
    component.save();
    expect(setTenantSpy).toHaveBeenCalled();
  });

  it('toggleGroup flips every row in the bucket', () => {
    const finance = component.buckets.find(b => b.group === 'FINANCE')!;
    component.toggleGroup(finance, false);
    for (const row of finance.rows) {
      expect(component.isEnabled(row)).toBeFalse();
    }
  });

  it('save with no changes is a no-op', () => {
    component.save();
    expect(configSpy.bulkUpsert).not.toHaveBeenCalled();
  });
});
