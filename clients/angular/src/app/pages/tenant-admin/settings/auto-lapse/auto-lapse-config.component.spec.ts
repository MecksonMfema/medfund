import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { FormsModule } from '@angular/forms';

import { AutoLapseConfigComponent } from './auto-lapse-config.component';
import { TenantService } from '../../../../core/services/tenant.service';
import { PermissionService } from '../../../../core/security/permission.service';
import { environment } from '../../../../../environments/environment';

describe('AutoLapseConfigComponent', () => {
  let fixture: ComponentFixture<AutoLapseConfigComponent>;
  let component: AutoLapseConfigComponent;
  let http: HttpTestingController;
  const baseUrl = environment.apiBaseUrl;
  const tenantId = 'tenant-uuid-1';

  const tenantServiceStub: Partial<TenantService> = {
    getTenantId: () => tenantId,
  };

  let permissionServiceStub: { has: (key: string) => boolean };

  beforeEach(async () => {
    // Fresh stub per test — one of the specs flips has() to false, so
    // sharing across tests would cascade the mutation.
    permissionServiceStub = { has: () => true };

    await TestBed.configureTestingModule({
      imports: [AutoLapseConfigComponent, FormsModule],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: TenantService, useValue: tenantServiceStub },
        { provide: PermissionService, useValue: permissionServiceStub },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(AutoLapseConfigComponent);
    component = fixture.componentInstance;
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('loads config on init and hydrates the form', () => {
    fixture.detectChanges();
    const req = http.expectOne(`${baseUrl}/tenants/${tenantId}/auto-lapse-config`);
    req.flush({
      tenantId,
      enabled: true,
      arrearsThresholdMonths: 4,
      graceWindowDays: 10,
      updatedAt: null,
      updatedBy: null,
      updatedByEmail: null,
    });

    expect(component.enabled).toBe(true);
    expect(component.arrearsThresholdMonths).toBe(4);
    expect(component.graceWindowDays).toBe(10);
  });

  it('rejects save when enabled=true but threshold is out of range', () => {
    fixture.detectChanges();
    http.expectOne(`${baseUrl}/tenants/${tenantId}/auto-lapse-config`).flush({
      tenantId, enabled: false, arrearsThresholdMonths: null, graceWindowDays: null,
      updatedAt: null, updatedBy: null, updatedByEmail: null,
    });

    component.enabled = true;
    component.arrearsThresholdMonths = 70; // out of range
    component.graceWindowDays = 7;
    component.save();

    expect(component.errorMessage).toContain('Arrears threshold');
    http.expectNone(r => r.method === 'PUT' && r.url.endsWith('/auto-lapse-config'));
  });

  it('PUTs the update payload on successful save', () => {
    fixture.detectChanges();
    http.expectOne(`${baseUrl}/tenants/${tenantId}/auto-lapse-config`).flush({
      tenantId, enabled: false, arrearsThresholdMonths: null, graceWindowDays: null,
      updatedAt: null, updatedBy: null, updatedByEmail: null,
    });

    component.enabled = true;
    component.arrearsThresholdMonths = 3;
    component.graceWindowDays = 7;
    component.save();

    const put = http.expectOne(r => r.method === 'PUT'
        && r.url === `${baseUrl}/tenants/${tenantId}/auto-lapse-config`);
    expect(put.request.body).toEqual({
      enabled: true,
      arrearsThresholdMonths: 3,
      graceWindowDays: 7,
    });
    put.flush({
      tenantId, enabled: true, arrearsThresholdMonths: 3, graceWindowDays: 7,
      updatedAt: '2026-08-22T00:00:00Z', updatedBy: null, updatedByEmail: 'x@x',
    });
    expect(component.saved).toBe(true);
    expect(component.errorMessage).toBeNull();
  });

  it('canConfigure returns false without permission', () => {
    permissionServiceStub.has = () => false;
    fixture.detectChanges();
    http.expectOne(`${baseUrl}/tenants/${tenantId}/auto-lapse-config`).flush({
      tenantId, enabled: false, arrearsThresholdMonths: null, graceWindowDays: null,
      updatedAt: null, updatedBy: null, updatedByEmail: null,
    });
    expect(component.canConfigure()).toBe(false);
  });
});
