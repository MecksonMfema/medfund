import { of } from 'rxjs';
import { CorrectionsPageComponent } from './corrections-page.component';
import {
  Adjustment,
  AdjustmentPage,
  CommissionAdjustmentService,
} from '../../../../../core/services/commission-adjustment.service';
import { PermissionService } from '../../../../../core/security/permission.service';

/**
 * Drives CorrectionsPageComponent directly. Confirms permission-based
 * rendering and the merged drafter/approver workflow work in a single
 * page.
 */
describe('CorrectionsPageComponent', () => {
  function permsStub(held: string[]): PermissionService {
    const set = new Set(held);
    return {
      hasAny: (keys: ReadonlyArray<string>) => keys.some(k => set.has(k)),
      has: (k: string) => set.has(k),
      isSuperAdmin: () => false,
      snapshot: () => set,
    } as unknown as PermissionService;
  }

  function emptyPage(): AdjustmentPage {
    return { content: [], total: 0, page: 0, size: 50, totalPages: 1 };
  }

  function row(overrides: Partial<Adjustment> = {}): Adjustment {
    return {
      id: 'a-1',
      reference: 'ADJ-1',
      targetCommissionTransactionId: 'tx-abcdef123',
      adjustmentType: 'EX_GRATIA',
      adjustmentAmount: 25,
      nativeCurrency: 'USD',
      justification: 'x'.repeat(30),
      status: 'DRAFT',
      actorEmail: 'drafter@example.test',
      ...overrides,
    };
  }

  function makeSvc(): jasmine.SpyObj<CommissionAdjustmentService> {
    return jasmine.createSpyObj<CommissionAdjustmentService>(
      'CommissionAdjustmentService',
      ['list', 'get', 'create', 'approve', 'commit', 'void'],
    );
  }

  it('defaults status filter to empty (All open) and calls list on init', () => {
    const svc = makeSvc();
    svc.list.and.returnValue(of(emptyPage()));
    const c = new CorrectionsPageComponent(svc, permsStub([
      'finance.commission:view',
    ]));
    c.ngOnInit();
    expect(c.statusFilter).toBe('');
    expect(svc.list).toHaveBeenCalledWith(undefined, 0, 50);
  });

  it('hides "New correction" button flag for a user without draft_adjustment', () => {
    const svc = makeSvc();
    svc.list.and.returnValue(of(emptyPage()));
    const c = new CorrectionsPageComponent(svc, permsStub([
      'finance.commission:approve_adjustment',
    ]));
    expect(c.canDraft).toBe(false);
    expect(c.canApprove).toBe(true);
  });

  it('shows New + hides row actions for drafter-only user', () => {
    const svc = makeSvc();
    svc.list.and.returnValue(of(emptyPage()));
    const c = new CorrectionsPageComponent(svc, permsStub([
      'finance.commission:draft_adjustment',
    ]));
    expect(c.canDraft).toBe(true);
    expect(c.canApprove).toBe(false);
  });

  it('view-only user gets read-only page (no draft, no approve)', () => {
    const svc = makeSvc();
    svc.list.and.returnValue(of(emptyPage()));
    const c = new CorrectionsPageComponent(svc, permsStub([
      'finance.commission:view',
    ]));
    expect(c.canDraft).toBe(false);
    expect(c.canApprove).toBe(false);
  });

  it('isTerminal returns true for COMMITTED and VOIDED, false for DRAFT/APPROVED', () => {
    const svc = makeSvc();
    svc.list.and.returnValue(of(emptyPage()));
    const c = new CorrectionsPageComponent(svc, permsStub(['finance.commission:view']));
    expect(c.isTerminal(row({ status: 'DRAFT' }))).toBe(false);
    expect(c.isTerminal(row({ status: 'APPROVED' }))).toBe(false);
    expect(c.isTerminal(row({ status: 'COMMITTED' }))).toBe(true);
    expect(c.isTerminal(row({ status: 'VOIDED' }))).toBe(true);
  });

  it('approve() invokes svc.approve then re-fetches', () => {
    const svc = makeSvc();
    svc.list.and.returnValue(of(emptyPage()));
    svc.approve.and.returnValue(of(row({ status: 'APPROVED' })));
    const c = new CorrectionsPageComponent(svc, permsStub([
      'finance.commission:approve_adjustment',
    ]));
    c.ngOnInit();
    svc.list.calls.reset();
    c.approve(row());
    expect(svc.approve).toHaveBeenCalledWith('a-1');
    expect(svc.list).toHaveBeenCalledTimes(1);
  });

  it('submitCreate() rejects a blank target then passes when filled', () => {
    const svc = makeSvc();
    svc.list.and.returnValue(of(emptyPage()));
    svc.create.and.returnValue(of(row()));
    const c = new CorrectionsPageComponent(svc, permsStub([
      'finance.commission:draft_adjustment',
    ]));
    c.ngOnInit();
    c.openCreate();
    c.submitCreate();
    expect(c.createError).toContain('Target');
    expect(svc.create).not.toHaveBeenCalled();

    c.form.targetReferenceQuery = 'tx-abcdef123';
    c.form.adjustmentAmount = 25;
    c.form.justification = 'A valid justification of at least 20 chars';
    c.submitCreate();
    expect(svc.create).toHaveBeenCalledTimes(1);
  });

  it('onStatusChange resets page and re-fetches with new filter', () => {
    const svc = makeSvc();
    svc.list.and.returnValue(of(emptyPage()));
    const c = new CorrectionsPageComponent(svc, permsStub(['finance.commission:view']));
    c.ngOnInit();
    svc.list.calls.reset();
    c.statusFilter = 'COMMITTED';
    c.page = 3;
    c.onStatusChange();
    expect(c.page).toBe(1);
    expect(svc.list).toHaveBeenCalledWith('COMMITTED', 0, 50);
  });
});
