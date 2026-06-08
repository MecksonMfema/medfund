import { of, throwError } from 'rxjs';
import { MemberFormComponent } from './member-form.component';
import { Member, MembersService } from '../../../../core/services/members.service';
import { ToastService } from '../../../../shared/components/toast/toast.service';

function makeMember(): Member {
  return {
    id: 'm-new', memberNumber: 'M-1234',
    firstName: 'New', lastName: 'Member', dateOfBirth: '1990-01-01',
    email: '', phone: '', status: 'enrolled',
    groupId: null, schemeId: null,
    enrollmentDate: '2026-01-01', createdAt: '2026-01-01',
  };
}

class StubMembers {
  enrollCalls: any[] = [];
  shouldFail = false;
  enroll = (data: any) => {
    this.enrollCalls.push(data);
    return this.shouldFail
      ? throwError(() => ({ error: { detail: 'duplicate id' } }))
      : of({ ...makeMember(), ...data });
  };
}
class StubToast {
  successes: string[] = []; errors: string[] = [];
  success = (m: string) => this.successes.push(m);
  error   = (m: string) => this.errors.push(m);
}
class StubRouter { navigated: any[] = []; navigate = (cmds: any[]) => this.navigated.push(cmds); }

function instantiate() {
  const members = new StubMembers();
  const router = new StubRouter();
  const toast = new StubToast();
  const comp = new MemberFormComponent(
    members as unknown as MembersService,
    router as any,
    toast as unknown as ToastService,
  );
  return { comp, members, router, toast };
}

describe('MemberFormComponent', () => {
  it('blocks submit when required fields are missing', () => {
    const { comp, members } = instantiate();
    comp.form.firstName = 'A';
    // last name and DOB still empty
    comp.submit();
    expect(members.enrollCalls.length).toBe(0);
    expect(comp.errorMessage).toContain('required');
  });

  it('enrols + navigates to the new member detail on success', () => {
    const { comp, members, router, toast } = instantiate();
    comp.form = {
      ...comp.form,
      firstName: 'Sarah', lastName: 'Doe', dateOfBirth: '1990-01-01',
      email: 'sarah@example.com',
    };
    comp.submit();
    expect(members.enrollCalls[0].email).toBe('sarah@example.com');
    expect(router.navigated[0]).toEqual(['/tenant/members', 'm-new']);
    expect(toast.successes[0]).toContain('Sarah Doe');
  });

  it('surfaces enrolment errors on the banner and via toast', () => {
    const { comp, members, toast } = instantiate();
    members.shouldFail = true;
    comp.form = { ...comp.form, firstName: 'X', lastName: 'Y', dateOfBirth: '2000-01-01' };
    comp.submit();
    expect(comp.errorMessage).toBe('duplicate id');
    expect(toast.errors[0]).toBe('duplicate id');
  });

  it('strips empty optional fields out of the payload', () => {
    const { comp, members } = instantiate();
    comp.form = {
      ...comp.form,
      firstName: 'A', lastName: 'B', dateOfBirth: '2000-01-01',
      gender: '', nationalId: '   ', email: '', phone: '', address: '',
      groupId: '', schemeId: '',
    };
    comp.submit();
    const sent = members.enrollCalls[0];
    expect(sent.gender).toBeUndefined();
    expect(sent.nationalId).toBeUndefined();
    expect(sent.email).toBeUndefined();
    expect(sent.groupId).toBeUndefined();
    expect(sent.schemeId).toBeUndefined();
  });
});
