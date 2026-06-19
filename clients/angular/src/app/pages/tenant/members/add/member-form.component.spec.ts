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

/** Build a form snapshot with every required field populated so the
 *  spec can assert one behaviour at a time without re-listing them. */
function withValidRequiredFields(comp: MemberFormComponent, overrides: Partial<any> = {}): void {
  comp.form = {
    ...comp.form,
    firstName:   'Sarah',
    lastName:    'Doe',
    dateOfBirth: '1990-01-01',
    gender:      'female',
    nationalId:  '63-1234567',
    email:       'sarah@example.com',
    schemeId:    'sch-1',
    ...overrides,
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
    // missing: last name, DOB, gender, national ID, email, scheme
    comp.submit();
    expect(members.enrollCalls.length).toBe(0);
    expect(comp.errorMessage).toContain('missing');
  });

  it('blocks submit when scheme is the only field missing', () => {
    const { comp, members } = instantiate();
    withValidRequiredFields(comp, { schemeId: '' });
    comp.submit();
    expect(members.enrollCalls.length).toBe(0);
    expect(comp.errorMessage).toContain('scheme');
  });

  it('enrols + navigates to the new member detail on success', () => {
    const { comp, members, router, toast } = instantiate();
    withValidRequiredFields(comp);
    comp.submit();
    expect(members.enrollCalls[0].email).toBe('sarah@example.com');
    expect(router.navigated[0]).toEqual(['/tenant/members', 'm-new']);
    expect(toast.successes[0]).toContain('Sarah Doe');
  });

  it('forces the enrolment date to the 1st of the chosen month', () => {
    const { comp, members } = instantiate();
    withValidRequiredFields(comp, { enrollmentDate: '2026-03-17' });
    comp.submit();
    expect(members.enrollCalls[0].enrollmentDate).toBe('2026-03-01');
  });

  it('snaps the enrollmentDate model on (ngModelChange)', () => {
    const { comp } = instantiate();
    comp.form.enrollmentDate = '2026-07-23';
    comp.onEnrollmentDateChange();
    expect(comp.form.enrollmentDate).toBe('2026-07-01');
  });

  it('surfaces enrolment errors on the banner and via toast', () => {
    const { comp, members, toast } = instantiate();
    members.shouldFail = true;
    withValidRequiredFields(comp);
    comp.submit();
    expect(comp.errorMessage).toBe('duplicate id');
    expect(toast.errors[0]).toBe('duplicate id');
  });

  it('strips empty optional fields out of the payload', () => {
    const { comp, members } = instantiate();
    withValidRequiredFields(comp, { phone: '', address: '', groupId: '' });
    comp.submit();
    const sent = members.enrollCalls[0];
    expect(sent.phone).toBeUndefined();
    expect(sent.address).toBeUndefined();
    expect(sent.groupId).toBeUndefined();
    // Required fields stay on the payload.
    expect(sent.gender).toBe('female');
    expect(sent.nationalId).toBe('63-1234567');
    expect(sent.email).toBe('sarah@example.com');
    expect(sent.schemeId).toBe('sch-1');
  });
});
