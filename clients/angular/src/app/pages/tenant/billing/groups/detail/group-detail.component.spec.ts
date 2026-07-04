import { of, throwError } from 'rxjs';
import { GroupDetailComponent } from './group-detail.component';
import { Group, GroupsService } from '../../../../../core/services/groups.service';
import { Member, MembersService } from '../../../../../core/services/members.service';
import { AdminService } from '../../../../../core/services/admin.service';
import { GroupLiaisonsService } from '../../../../../core/services/group-liaisons.service';
import { TenantService } from '../../../../../core/services/tenant.service';
import { ToastService } from '../../../../../shared/components/toast/toast.service';

function makeGroup(overrides: Partial<Group> = {}): Group {
  return {
    id: 'g-1', name: 'Acme Corp',
    registrationNumber: 'REG-1',
    address: '1 Main St',
    liaisonKind: 'MEMBER', liaisonUserId: 'm-1',
    status: 'ACTIVE',
    ...overrides,
  };
}

function makeMember(overrides: Partial<Member> = {}): Member {
  return {
    id: 'm-1', memberNumber: 'M-0001',
    firstName: 'Sarah', lastName: 'Doe',
    dateOfBirth: '1990-01-01',
    email: 'sarah@acme.com', phone: '',
    status: 'active', groupId: 'g-1', schemeId: null,
    enrollmentDate: '2024-01-01', createdAt: '2024-01-01',
    ...overrides,
  };
}

class StubGroups {
  group = makeGroup();
  updateCalls: any[] = [];
  suspendCalls = 0;
  shouldFailUpdate = false;
  findById = (_id: string) => of(this.group);
  update = (id: string, data: any) => {
    this.updateCalls.push({ id, data });
    return this.shouldFailUpdate
      ? throwError(() => ({ error: { detail: 'nope' } }))
      : of({ ...this.group, ...data });
  };
  suspend = (_id: string) => { this.suspendCalls++; return of({ ...this.group, status: 'SUSPENDED' }); };
}

class StubMembers {
  members: Member[] = [makeMember()];
  getByGroupId = (_id: string) => of(this.members);
  // GroupDetailComponent.loadLiaisonLabel resolves the display name of
  // the group's liaison via getById; stub with a canned member so the
  // afterAll assertion doesn't hit a TypeError.
  getById = (_id: string) => of(makeMember());
}

class StubToast {
  successes: string[] = []; errors: string[] = [];
  success = (m: string) => this.successes.push(m);
  error   = (m: string) => this.errors.push(m);
}

class StubRouter   { navigated: any[] = []; navigate = (cmds: any[]) => this.navigated.push(cmds); }
class StubAdmin    { getStaffUserById = (_id: string) => of({ id: _id, firstName: 'A', lastName: 'B', email: 'a@b' } as any); }
class StubLiaisons { getById = (_id: string) => of({ id: _id, firstName: 'P', lastName: 'L', email: 'p@l', status: 'invited' } as any); }
class StubTenant   { getTenantId = () => 't-1'; }

function instantiate(id: string | null = 'g-1') {
  const route = { snapshot: { paramMap: { get: (_k: string) => id } } } as any;
  const groups = new StubGroups();
  const members = new StubMembers();
  const admin = new StubAdmin();
  const liaisons = new StubLiaisons();
  const tenant = new StubTenant();
  const toast = new StubToast();
  const router = new StubRouter();
  const comp = new GroupDetailComponent(
    groups as unknown as GroupsService,
    members as unknown as MembersService,
    admin as unknown as AdminService,
    liaisons as unknown as GroupLiaisonsService,
    tenant as unknown as TenantService,
    route,
    router as any,
    toast as unknown as ToastService,
  );
  return { comp, groups, members, toast, router };
}

describe('GroupDetailComponent', () => {
  beforeEach(() => spyOn(window, 'confirm').and.returnValue(true));

  it('loads group + members on init', () => {
    const { comp } = instantiate();
    comp.ngOnInit();
    expect(comp.group?.name).toBe('Acme Corp');
    expect(comp.form.registrationNumber).toBe('REG-1');
    expect(comp.members.length).toBe(1);
  });

  it('updates the group and surfaces a success toast', () => {
    const { comp, groups, toast } = instantiate();
    comp.ngOnInit();
    comp.form.address = '5 Park Lane';
    comp.save();
    expect(groups.updateCalls[0].data.address).toBe('5 Park Lane');
    expect(toast.successes[0]).toBe('Group updated');
  });

  it('blocks save when the liaison has been cleared', () => {
    const { comp, groups } = instantiate();
    comp.ngOnInit();
    comp.form.liaisonKind = 'CLEAR';
    comp.form.liaisonUserId = null;
    comp.save();
    expect(groups.updateCalls.length).toBe(0);
    // Error copy was reworded from "liaison is required" to the current
    // "either a liaison or a contact email" formulation when the email
    // fallback landed. The invariant is that save is blocked and the
    // user sees a message mentioning the liaison route.
    expect(comp.errorMessage).toContain('liaison');
  });

  it('blocks save when name is empty', () => {
    const { comp, groups } = instantiate();
    comp.ngOnInit();
    comp.form.name = '   ';
    comp.save();
    expect(groups.updateCalls.length).toBe(0);
    expect(comp.errorMessage).toBe('Name is required');
  });

  it('surfaces save errors in the banner and toast', () => {
    const { comp, groups, toast } = instantiate();
    comp.ngOnInit();
    groups.shouldFailUpdate = true;
    comp.save();
    expect(comp.errorMessage).toBe('nope');
    expect(toast.errors[0]).toBe('nope');
  });

  it('suspends an active group', () => {
    const { comp, groups } = instantiate();
    comp.ngOnInit();
    comp.suspend();
    expect(groups.suspendCalls).toBe(1);
    expect(comp.group?.status).toBe('SUSPENDED');
  });

  it('opens a member on row click', () => {
    const { comp, router } = instantiate();
    comp.ngOnInit();
    comp.openMember(comp.members[0]);
    expect(router.navigated[0]).toEqual(['/tenant/members', 'm-1']);
  });
});
