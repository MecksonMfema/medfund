import { BehaviorSubject, Observable } from 'rxjs';
import { Tenant, TenantService } from '../core/services/tenant.service';

export class MockTenantService implements Partial<TenantService> {
  private subject: BehaviorSubject<Tenant | null>;
  tenant$: Observable<Tenant | null>;

  constructor(initial: Tenant | null = null) {
    this.subject = new BehaviorSubject<Tenant | null>(initial);
    this.tenant$ = this.subject.asObservable();
  }

  getTenant = (): Tenant | null => this.subject.getValue();
  getTenantId = (): string => this.subject.getValue()?.id ?? '';
  setTenant = (t: Tenant): void => this.subject.next(t);
  clearTenant = (): void => this.subject.next(null);
}

export function buildTenant(overrides: Partial<Tenant> = {}): Tenant {
  return {
    id: 'tenant-1',
    name: 'Acme Health',
    slug: 'acme',
    status: 'active',
    timezone: 'Africa/Harare',
    insuranceLines: ['HEALTH'],
    providerRegLabel: 'AHFOZ / Practice Number',
    ...overrides,
  };
}

export function provideMockTenant(initial: Tenant | null = buildTenant()) {
  return { provide: TenantService, useValue: new MockTenantService(initial) };
}
