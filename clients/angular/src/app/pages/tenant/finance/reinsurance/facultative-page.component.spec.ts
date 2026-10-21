import { FacultativePageComponent } from './facultative-page.component';
import { PermissionService } from '../../../../core/security/permission.service';

/**
 * Drives the FacultativePageComponent class directly. Confirms
 * tab-visibility + default-tab logic across the four permission
 * combinations declared in the plan (G3/G4).
 */
describe('FacultativePageComponent', () => {
  function permsStub(held: string[]): PermissionService {
    const set = new Set(held);
    return {
      hasAny: (keys: ReadonlyArray<string>) => keys.some(k => set.has(k)),
      has: (k: string) => set.has(k),
      isSuperAdmin: () => false,
      snapshot: () => set,
    } as unknown as PermissionService;
  }

  it('cede-only user sees Cedable claims tab, defaults there', () => {
    const c = new FacultativePageComponent(permsStub(['finance.reinsurance:cede_facultative']));
    expect(c.showCandidatesTab).toBe(true);
    expect(c.showQueueTab).toBe(false);
    expect(c.activeTab).toBe('candidates');
  });

  it('view-only user sees Cession queue tab, defaults there', () => {
    const c = new FacultativePageComponent(permsStub(['finance.reinsurance:view']));
    expect(c.showCandidatesTab).toBe(false);
    expect(c.showQueueTab).toBe(true);
    expect(c.activeTab).toBe('queue');
  });

  it('approve-only user sees Cession queue tab, defaults there', () => {
    const c = new FacultativePageComponent(permsStub(['finance.reinsurance:approve_facultative']));
    expect(c.showCandidatesTab).toBe(false);
    expect(c.showQueueTab).toBe(true);
    expect(c.activeTab).toBe('queue');
  });

  it('user with both cede + approve sees both tabs, defaults to Cession queue', () => {
    const c = new FacultativePageComponent(permsStub([
      'finance.reinsurance:cede_facultative',
      'finance.reinsurance:approve_facultative',
    ]));
    expect(c.showCandidatesTab).toBe(true);
    expect(c.showQueueTab).toBe(true);
    expect(c.activeTab).toBe('queue');
  });

  it('setTab flips activeTab', () => {
    const c = new FacultativePageComponent(permsStub([
      'finance.reinsurance:cede_facultative',
      'finance.reinsurance:approve_facultative',
    ]));
    expect(c.activeTab).toBe('queue');
    c.setTab('candidates');
    expect(c.activeTab).toBe('candidates');
    c.setTab('queue');
    expect(c.activeTab).toBe('queue');
  });
});
