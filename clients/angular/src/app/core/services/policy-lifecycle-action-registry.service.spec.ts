import { TestBed } from '@angular/core/testing';
import {
  PolicyLifecycleActionRegistryService,
  PolicySource,
} from './policy-lifecycle-action-registry.service';

describe('PolicyLifecycleActionRegistryService', () => {
  let svc: PolicyLifecycleActionRegistryService;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    svc = TestBed.inject(PolicyLifecycleActionRegistryService);
  });

  describe('actionsFor()', () => {
    it('returns three actions when status is active', () => {
      expect(svc.actionsFor('active')).toEqual(['lapse', 'terminate', 'suspend']);
    });

    it('returns reinstate + lapse + terminate for suspended', () => {
      expect(svc.actionsFor('suspended')).toEqual(['reinstate', 'lapse', 'terminate']);
    });

    it('returns reinstate + terminate for lapsed', () => {
      expect(svc.actionsFor('lapsed')).toEqual(['reinstate', 'terminate']);
    });

    it('returns empty for terminated (no actions on a closed policy)', () => {
      expect(svc.actionsFor('terminated')).toEqual([]);
    });

    it('is case-insensitive on the status', () => {
      expect(svc.actionsFor('ACTIVE')).toEqual(['lapse', 'terminate', 'suspend']);
    });

    it('returns empty for unknown status without throwing', () => {
      expect(svc.actionsFor('bogus')).toEqual([]);
    });
  });

  describe('reasonsFor()', () => {
    it('LIFE has MORTALITY in its vocab', () => {
      const codes = svc.reasonsFor('LIFE_POLICY').map(r => r.code);
      expect(codes).toContain('MORTALITY');
    });

    it('FUNERAL does not have MORTALITY', () => {
      const codes = svc.reasonsFor('FUNERAL_POLICY').map(r => r.code);
      expect(codes).not.toContain('MORTALITY');
    });

    it('TRAVEL has TRIP_CANCELLED but not MORTALITY', () => {
      const codes = svc.reasonsFor('TRAVEL_POLICY').map(r => r.code);
      expect(codes).toContain('TRIP_CANCELLED');
      expect(codes).not.toContain('MORTALITY');
    });

    it('VEHICLE has TOTAL_LOSS + STORAGE_SUSPEND but not INSURED_EVENT', () => {
      const codes = svc.reasonsFor('VEHICLE_POLICY').map(r => r.code);
      expect(codes).toContain('TOTAL_LOSS');
      expect(codes).toContain('STORAGE_SUSPEND');
      expect(codes).not.toContain('INSURED_EVENT');
    });

    it('PROPERTY has TOTAL_LOSS but not STORAGE_SUSPEND', () => {
      const codes = svc.reasonsFor('PROPERTY_POLICY').map(r => r.code);
      expect(codes).toContain('TOTAL_LOSS');
      expect(codes).not.toContain('STORAGE_SUSPEND');
    });

    it('every source includes ADMIN_CORRECTION as an escape hatch', () => {
      const sources: PolicySource[] = [
        'LIFE_POLICY', 'FUNERAL_POLICY', 'DISABILITY_POLICY',
        'TRAVEL_POLICY', 'VEHICLE_POLICY', 'PROPERTY_POLICY',
      ];
      for (const s of sources) {
        const codes = svc.reasonsFor(s).map(r => r.code);
        expect(codes).withContext(s).toContain('ADMIN_CORRECTION');
      }
    });
  });

  it('endpointFor() builds the correct REST path per source + action', () => {
    expect(svc.endpointFor('LIFE_POLICY', 'abc', 'lapse')).toBe('/life-policies/abc/lapse');
    expect(svc.endpointFor('VEHICLE_POLICY', 'v1', 'terminate')).toBe('/vehicle-policies/v1/terminate');
    expect(svc.endpointFor('PROPERTY_POLICY', 'p1', 'reinstate')).toBe('/property-policies/p1/reinstate');
  });
});
