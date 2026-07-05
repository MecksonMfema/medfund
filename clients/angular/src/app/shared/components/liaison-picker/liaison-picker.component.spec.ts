import { of, throwError } from 'rxjs';
import { LiaisonPickerComponent } from './liaison-picker.component';
import { AdminService, StaffUser } from '../../../core/services/admin.service';
import { GroupLiaison, GroupLiaisonsService } from '../../../core/services/group-liaisons.service';
import { Member, MembersService } from '../../../core/services/members.service';
import { TenantService } from '../../../core/services/tenant.service';
import { ToastService } from '../toast/toast.service';

/**
 * Guards the picker contract: search-only for MEMBER + STAFF, plus an
 * inline collapsible "add new liaison" flow for the LIAISON kind. The
 * inline flow exists so an operator mid-way through a group create
 * doesn't lose their draft when they need to invite a fresh liaison.
 */

function makeMember(overrides: Partial<Member> = {}): Member {
  return {
    id: 'm-1', memberNumber: 'M-001',
    firstName: 'Sarah', lastName: 'Doe',
    dateOfBirth: '1990-01-01',
    email: 's@x', phone: '',
    status: 'active', groupId: null, schemeId: null,
    enrollmentDate: '2024-01-01', createdAt: '2024-01-01',
    ...overrides,
  };
}

function makeStaff(overrides: Partial<StaffUser> = {}): StaffUser {
  return {
    id: 'su-1', firstName: 'Alice', lastName: 'Smith',
    email: 'alice@org', status: 'active', realmRole: 'operations',
    tenantId: 't-1',
    ...overrides,
  } as StaffUser;
}

class StubMembers {
  searchCalls: string[] = [];
  searchResult: Member[] = [makeMember()];
  searchByName = (q: string) => { this.searchCalls.push(q); return of(this.searchResult); };
}

class StubAdmin {
  searchCalls: string[] = [];
  searchResult: StaffUser[] = [makeStaff()];
  searchStaffUsers = (q: string, _tenantId?: string) => { this.searchCalls.push(q); return of(this.searchResult); };
}

class StubLiaisons {
  searchCalls: string[] = [];
  createCalls: any[] = [];
  searchResult: GroupLiaison[] = [];
  createResult: GroupLiaison = { id: 'lia-new', firstName: 'New', lastName: 'Liaison', email: 'n@l', status: 'invited' };
  createFails = false;
  search = (q: string) => { this.searchCalls.push(q); return of(this.searchResult); };
  create = (data: any) => {
    this.createCalls.push(data);
    return this.createFails
      ? throwError(() => ({ error: { detail: 'boom' } }))
      : of({ ...this.createResult, ...data });
  };
}

class StubTenant { getTenantId = () => 't-1'; }

class StubToast {
  errors: string[] = [];
  successes: string[] = [];
  error = (m: string) => this.errors.push(m);
  success = (m: string) => this.successes.push(m);
}

function instantiate() {
  const members = new StubMembers();
  const admin = new StubAdmin();
  const liaisons = new StubLiaisons();
  const tenant = new StubTenant();
  const toast = new StubToast();
  const comp = new LiaisonPickerComponent(
    members as unknown as MembersService,
    admin as unknown as AdminService,
    liaisons as unknown as GroupLiaisonsService,
    tenant as unknown as TenantService,
    toast as unknown as ToastService,
  );
  comp.ngOnInit();
  return { comp, members, admin, liaisons, tenant, toast };
}

describe('LiaisonPickerComponent', () => {

  // ------------------------------------------------------------------
  // Search-only surface — MEMBER + STAFF + LIAISON pick routing.
  // ------------------------------------------------------------------

  it('defaults to MEMBER kind', () => {
    const { comp } = instantiate();
    expect(comp.activeKind).toBe('MEMBER');
  });

  it('renders the prefill chip when value+kind+prefillLabel are supplied', () => {
    const { comp } = instantiate();
    comp.value = 'm-1';
    comp.kind = 'MEMBER';
    comp.prefillLabel = 'Sarah Doe';
    comp.prefillSublabel = 'M-001';
    comp.ngOnChanges({
      value: { currentValue: 'm-1', previousValue: null, firstChange: true, isFirstChange: () => true },
      prefillLabel: { currentValue: 'Sarah Doe', previousValue: null, firstChange: true, isFirstChange: () => true },
    } as any);
    expect(comp.picked?.label).toBe('Sarah Doe');
    expect(comp.activeKind).toBe('MEMBER');
  });

  it('emits a typed selection when a member is picked', () => {
    const { comp } = instantiate();
    let emitted: any = null;
    comp.selected.subscribe(s => emitted = s);
    comp.pick({ id: 'm-1', label: 'Sarah Doe', sublabel: 'M-001' });
    expect(emitted).toEqual({ kind: 'MEMBER', id: 'm-1', label: 'Sarah Doe', sublabel: 'M-001' });
  });

  it('switches the emitted kind when the tab toggles to STAFF and a pick lands', () => {
    const { comp } = instantiate();
    comp.setKind('STAFF');
    let emitted: any = null;
    comp.selected.subscribe(s => emitted = s);
    comp.pick({ id: 'su-1', label: 'Alice Smith', sublabel: 'alice@org' });
    expect(emitted.kind).toBe('STAFF');
  });

  it('switches the emitted kind when the tab toggles to LIAISON', () => {
    const { comp } = instantiate();
    comp.setKind('LIAISON');
    let emitted: any = null;
    comp.selected.subscribe(s => emitted = s);
    comp.pick({ id: 'lia-1', label: 'Paula L', sublabel: 'p@l' });
    expect(emitted.kind).toBe('LIAISON');
  });

  it('setKind clears the current query + matches + collapses inline add', () => {
    // Regression guard: leaving showAddNew=true on a tab switch would
    // present the LIAISON invite form under MEMBER / STAFF — visually
    // wrong and functionally confusing.
    const { comp } = instantiate();
    comp.setKind('LIAISON');
    comp.openAddNew();
    comp.query = 'stale';
    comp.matches = [{ id: 'x', label: 'x' }];
    comp.showMatches = true;

    comp.setKind('STAFF');

    expect(comp.query).toBe('');
    expect(comp.matches).toEqual([]);
    expect(comp.showMatches).toBeFalse();
    expect(comp.showAddNew).toBeFalse();
  });

  it('emits null on clear', () => {
    const { comp } = instantiate();
    comp.pick({ id: 'm-1', label: 'X' });
    let emitted: any = 'sentinel';
    comp.selected.subscribe(s => emitted = s);
    comp.clear();
    expect(emitted).toBeNull();
    expect(comp.value).toBeNull();
    expect(comp.kind).toBeNull();
  });

  // ------------------------------------------------------------------
  // Inline "add new liaison" — LIAISON kind only. This is why the
  // standalone page was rolled back: an operator filling a group
  // form must not lose their draft to invite a fresh liaison.
  // ------------------------------------------------------------------

  describe('inline add-new (LIAISON only)', () => {
    it('opens on openAddNew and starts with a blank form', () => {
      const { comp } = instantiate();
      comp.setKind('LIAISON');
      comp.newLiaison = { firstName: 'stale', lastName: 'x', email: 'x', phone: '', address: '' };

      comp.openAddNew();

      expect(comp.showAddNew).toBeTrue();
      // Reset guard — a stale form from a previous invite must not
      // resurrect when the operator re-opens the collapsible.
      expect(comp.newLiaison.firstName).toBe('');
      expect(comp.newLiaison.lastName).toBe('');
    });

    it('blocks submit and toasts when required fields are missing', () => {
      const { comp, toast, liaisons } = instantiate();
      comp.setKind('LIAISON');
      comp.openAddNew();
      comp.newLiaison = { firstName: '  ', lastName: 'B', email: 'a@b', phone: '', address: '' };

      comp.submitNew();

      expect(liaisons.createCalls.length).toBe(0);
      expect(toast.errors[0]).toContain('required');
    });

    it('trims fields, invites the liaison, auto-selects, and toasts success', () => {
      // Trimming guard: the create payload must not carry the
      // operator's incidental whitespace. Auto-select: the picker
      // fires the selected event so the parent group form sees the
      // new liaison without a manual pick — that's the whole point
      // of the inline flow (draft state preserved).
      const { comp, liaisons, toast } = instantiate();
      comp.setKind('LIAISON');
      comp.openAddNew();
      comp.newLiaison = {
        firstName: '  Sarah  ',
        lastName: '  Nkomo  ',
        email: '  sarah@x  ',
        phone: '   ',
        address: '',
      };
      let emitted: any = null;
      comp.selected.subscribe(s => emitted = s);

      comp.submitNew();

      expect(liaisons.createCalls[0]).toEqual(jasmine.objectContaining({
        firstName: 'Sarah',
        lastName:  'Nkomo',
        email:     'sarah@x',
        phone:     undefined,
        address:   undefined,
      }));
      expect(comp.showAddNew).toBeFalse();
      expect(comp.picked?.id).toBe('lia-new');
      expect(emitted?.kind).toBe('LIAISON');
      expect(toast.successes[0]).toContain('Sarah Nkomo');
    });

    it('leaves the form open + toasts on backend failure', () => {
      // Regression guard: on error we must NOT hide the form —
      // otherwise the operator loses the data they just typed and
      // has to re-enter it after seeing the toast.
      const { comp, liaisons, toast } = instantiate();
      liaisons.createFails = true;
      comp.setKind('LIAISON');
      comp.openAddNew();
      comp.newLiaison = { firstName: 'A', lastName: 'B', email: 'a@b', phone: '', address: '' };

      comp.submitNew();

      expect(comp.showAddNew).toBeTrue();
      expect(comp.picked).toBeNull();
      expect(toast.errors[0]).toBe('boom');
    });

    it('cancelAddNew collapses the form without emitting a selection', () => {
      const { comp } = instantiate();
      comp.setKind('LIAISON');
      comp.openAddNew();
      let emitted: any = 'sentinel';
      comp.selected.subscribe(s => emitted = s);

      comp.cancelAddNew();

      expect(comp.showAddNew).toBeFalse();
      // Cancel is not a selection — no emission at all (sentinel
      // stays untouched). A regression that also fired selected
      // here would mangle the parent form's liaisonKind state.
      expect(emitted).toBe('sentinel');
    });
  });
});
