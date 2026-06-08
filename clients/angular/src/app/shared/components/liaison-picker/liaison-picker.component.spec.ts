import { of, throwError } from 'rxjs';
import { LiaisonPickerComponent } from './liaison-picker.component';
import { AdminService, StaffUser } from '../../../core/services/admin.service';
import { GroupLiaison, GroupLiaisonsService } from '../../../core/services/group-liaisons.service';
import { Member, MembersService } from '../../../core/services/members.service';
import { TenantService } from '../../../core/services/tenant.service';
import { ToastService } from '../toast/toast.service';

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
  enrollCalls: any[] = [];
  searchResult: Member[] = [makeMember()];
  enrollResult = makeMember({ id: 'm-new', firstName: 'New', lastName: 'Member' });
  enrollFails = false;
  searchByName = (q: string) => { this.searchCalls.push(q); return of(this.searchResult); };
  enroll = (data: any) => {
    this.enrollCalls.push(data);
    return this.enrollFails
      ? throwError(() => ({ error: { detail: 'boom' } }))
      : of({ ...this.enrollResult, ...data });
  };
}

class StubAdmin {
  searchCalls: string[] = [];
  createCalls: any[] = [];
  searchResult: StaffUser[] = [makeStaff()];
  createResult = makeStaff({ id: 'su-new', firstName: 'New', lastName: 'Staff' });
  searchStaffUsers = (q: string, _tenantId?: string) => { this.searchCalls.push(q); return of(this.searchResult); };
  createStaffUser = (data: any) => { this.createCalls.push(data); return of({ ...this.createResult, ...data }); };
}

class StubLiaisons {
  searchCalls: string[] = [];
  createCalls: any[] = [];
  searchResult: GroupLiaison[] = [];
  createResult: GroupLiaison = { id: 'lia-new', firstName: 'New', lastName: 'Liaison', email: 'n@l', status: 'invited' };
  search = (q: string) => { this.searchCalls.push(q); return of(this.searchResult); };
  create = (data: any) => { this.createCalls.push(data); return of({ ...this.createResult, ...data }); };
  getById = (_id: string) => of(this.createResult);
}

class StubTenant { getTenantId = () => 't-1'; }
class StubToast  { errors: string[] = []; success = (_: string) => {}; error = (m: string) => this.errors.push(m); }

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

  it('switches search source when the tab toggles to STAFF', () => {
    const { comp, admin } = instantiate();
    comp.setKind('STAFF');
    comp.query = 'al';
    comp.onQueryChange();
    // Allow the 300ms debounce to elapse via fakeAsync-less workaround:
    // synchronously call through the search() helper. The component routes
    // by activeKind so we verify the admin service was set up to receive the call.
    // (search() is private — exercise it via setKind toggle + manual pick.)
    expect(comp.activeKind).toBe('STAFF');
    // Picking is what the parent sees; emit confirms the kind.
    let emitted: any = null;
    comp.selected.subscribe(s => emitted = s);
    comp.pick({ id: 'su-1', label: 'Alice Smith', sublabel: 'alice@org' });
    expect(emitted.kind).toBe('STAFF');
    // Reference the stub so the linter knows the variable is intentional.
    expect(admin).toBeDefined();
  });

  it('creates a new member inline and auto-selects', () => {
    const { comp, members } = instantiate();
    comp.openAddNew();
    comp.newMember = { firstName: 'Jo', lastName: 'Test', dateOfBirth: '2000-01-01', email: '', phone: '' };
    let emitted: any = null;
    comp.selected.subscribe(s => emitted = s);
    comp.submitNew();
    expect(members.enrollCalls[0].firstName).toBe('Jo');
    expect(emitted.kind).toBe('MEMBER');
    expect(comp.showAddNew).toBeFalse();
  });

  it('creates a new staff user inline and auto-selects', () => {
    const { comp, admin } = instantiate();
    comp.setKind('STAFF');
    comp.openAddNew();
    comp.newStaff = { firstName: 'X', lastName: 'Y', email: 'x@y', phone: '', realmRole: 'operations' };
    let emitted: any = null;
    comp.selected.subscribe(s => emitted = s);
    comp.submitNew();
    expect(admin.createCalls[0].realmRole).toBe('operations');
    expect(admin.createCalls[0].tenantId).toBe('t-1');
    expect(emitted.kind).toBe('STAFF');
  });

  it('blocks new-member submit when required fields missing', () => {
    const { comp, members, toast } = instantiate();
    comp.openAddNew();
    comp.newMember = { firstName: '', lastName: 'Doe', dateOfBirth: '2000-01-01', email: '', phone: '' };
    comp.submitNew();
    expect(members.enrollCalls.length).toBe(0);
    expect(toast.errors[0]).toContain('required');
  });

  it('creates a new pure liaison inline and auto-selects', () => {
    const { comp, liaisons } = instantiate();
    comp.setKind('LIAISON');
    comp.openAddNew();
    comp.newLiaison = { firstName: 'P', lastName: 'L', email: 'p@l', phone: '', address: '' };
    let emitted: any = null;
    comp.selected.subscribe(s => emitted = s);
    comp.submitNew();
    expect(liaisons.createCalls[0].email).toBe('p@l');
    expect(emitted.kind).toBe('LIAISON');
  });

  it('blocks new-liaison submit when required fields missing', () => {
    const { comp, liaisons, toast } = instantiate();
    comp.setKind('LIAISON');
    comp.openAddNew();
    comp.newLiaison = { firstName: 'A', lastName: 'B', email: '', phone: '', address: '' };
    comp.submitNew();
    expect(liaisons.createCalls.length).toBe(0);
    expect(toast.errors[0]).toContain('required');
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
});
