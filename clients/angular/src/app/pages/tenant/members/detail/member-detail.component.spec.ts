import { of, throwError } from 'rxjs';
import { MemberDetailComponent } from './member-detail.component';
import { Dependant, Member, MembersService } from '../../../../core/services/members.service';
import { ContributionsService } from '../../../../core/services/contributions.service';
import { GroupsService } from '../../../../core/services/groups.service';
import { ToastService } from '../../../../shared/components/toast/toast.service';

function makeMember(overrides: Partial<Member> = {}): Member {
  return {
    id: 'm-1',
    memberNumber: 'M-0001',
    firstName: 'Sarah',
    lastName: 'Doe',
    dateOfBirth: '1990-01-01',
    email: 'sarah@example.com',
    phone: '+263',
    status: 'active',
    groupId: null,
    schemeId: null,
    enrollmentDate: '2024-01-01',
    createdAt: '2024-01-01',
    ...overrides,
  };
}

function makeDependant(overrides: Partial<Dependant> = {}): Dependant {
  return {
    id: 'd-1', memberId: 'm-1',
    firstName: 'Lily', lastName: 'Doe',
    relationship: 'child', status: 'active',
    ...overrides,
  };
}

class StubMembers {
  member: Member = makeMember();
  dependants: Dependant[] = [makeDependant()];
  getByIdCalls = 0;
  updateCalls: any[] = [];
  addDependantCalls: any[] = [];
  updateDependantCalls: any[] = [];
  removeCalls: string[] = [];

  shouldFailGetMember = false;
  shouldFailUpdate = false;

  getById = (_id: string) => {
    this.getByIdCalls++;
    return this.shouldFailGetMember
      ? throwError(() => ({ error: { detail: 'boom' } }))
      : of(this.member);
  };
  update = (id: string, data: any) => {
    this.updateCalls.push({ id, data });
    return this.shouldFailUpdate
      ? throwError(() => ({ error: { detail: 'nope' } }))
      : of({ ...this.member, ...data });
  };
  getDependants = (_id: string) => of(this.dependants);
  addDependant = (data: any) => {
    this.addDependantCalls.push(data);
    return of(makeDependant({ id: 'd-new', ...data }));
  };
  updateDependant = (id: string, data: any) => {
    this.updateDependantCalls.push({ id, data });
    return of({ ...this.dependants.find(d => d.id === id)!, ...data });
  };
  removeDependant = (id: string) => {
    this.removeCalls.push(id);
    return of({ ...this.dependants.find(d => d.id === id)!, status: 'removed' });
  };
  activate  = () => of(this.member);
  suspend   = () => of(this.member);
  terminate = () => of(this.member);
}

class StubToast {
  successes: string[] = []; errors: string[] = [];
  success = (m: string) => this.successes.push(m);
  error   = (m: string) => this.errors.push(m);
}

class StubRouter { navigated: any[] = []; navigate = (cmds: any[]) => this.navigated.push(cmds); }

class StubGroups   { findById = (_id: string) => of({ id: _id, name: 'Acme', status: 'ACTIVE' } as any); }
class StubContribs { getSchemeById = (_id: string) => of({ id: _id, name: 'Gold', status: 'active', effectiveDate: '' } as any); }

function instantiate(id: string | null = 'm-1') {
  const route = { snapshot: { paramMap: { get: (_k: string) => id } } } as any;
  const members = new StubMembers();
  const groups = new StubGroups();
  const contribs = new StubContribs();
  const toast = new StubToast();
  const router = new StubRouter();
  const comp = new MemberDetailComponent(
    members as unknown as MembersService,
    groups as unknown as GroupsService,
    contribs as unknown as ContributionsService,
    route,
    router as any,
    toast as unknown as ToastService,
  );
  return { comp, members, toast, router };
}

describe('MemberDetailComponent', () => {
  beforeEach(() => spyOn(window, 'confirm').and.returnValue(true));

  it('loads member + dependants on init', () => {
    const { comp, members } = instantiate();
    comp.ngOnInit();
    expect(members.getByIdCalls).toBe(1);
    expect(comp.member?.firstName).toBe('Sarah');
    expect(comp.form.firstName).toBe('Sarah');
    expect(comp.dependants.length).toBe(1);
  });

  it('updates the member via save() and surfaces a success toast', () => {
    const { comp, members, toast } = instantiate();
    comp.ngOnInit();
    comp.form.email = 'new@example.com';
    comp.save();
    expect(members.updateCalls[0].data.email).toBe('new@example.com');
    expect(toast.successes[0]).toBe('Member updated');
  });

  it('shows an error banner when save fails', () => {
    const { comp, members } = instantiate();
    comp.ngOnInit();
    members.shouldFailUpdate = true;
    comp.save();
    expect(comp.errorMessage).toBe('nope');
  });

  it('adds a new dependant via the inline form', () => {
    const { comp, members } = instantiate();
    comp.ngOnInit();
    comp.startAddDependant();
    comp.dependantForm = { firstName: 'Joe', lastName: 'Doe', dateOfBirth: '2020-05-01', gender: 'male', relationship: 'child', nationalId: '' };
    comp.saveDependant();
    expect(members.addDependantCalls[0].memberId).toBe('m-1');
    expect(comp.dependants.length).toBe(2);
    expect(comp.editingDependantId).toBeNull();
  });

  it('updates an existing dependant via updateDependant', () => {
    const { comp, members } = instantiate();
    comp.ngOnInit();
    comp.editDependant(comp.dependants[0]);
    comp.dependantForm.relationship = 'spouse';
    comp.saveDependant();
    expect(members.updateDependantCalls[0].id).toBe('d-1');
    expect(members.updateDependantCalls[0].data.relationship).toBe('spouse');
  });

  it('soft-removes a dependant and reflects the new status', () => {
    const { comp, members } = instantiate();
    comp.ngOnInit();
    comp.removeDependant(comp.dependants[0]);
    expect(members.removeCalls[0]).toBe('d-1');
    expect(comp.dependants[0].status).toBe('removed');
  });

  it('rejects dependant save when required fields are missing', () => {
    const { comp, members, toast } = instantiate();
    comp.ngOnInit();
    comp.startAddDependant();
    comp.dependantForm = { ...comp.dependantForm, firstName: '' };
    comp.saveDependant();
    expect(members.addDependantCalls.length).toBe(0);
    expect(toast.errors[0]).toContain('required');
  });

  it('only shows status-actions for the current legal transitions', () => {
    const { comp } = instantiate();
    comp.member = makeMember({ status: 'active' });
    expect(comp.canActivate()).toBeFalse();
    expect(comp.canSuspend()).toBeTrue();
    expect(comp.canTerminate()).toBeTrue();

    comp.member = makeMember({ status: 'terminated' });
    expect(comp.canActivate()).toBeFalse();
    expect(comp.canSuspend()).toBeFalse();
    expect(comp.canTerminate()).toBeFalse();
  });
});
