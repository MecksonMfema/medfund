import { of, throwError } from 'rxjs';
import { ActivatedRoute, Router } from '@angular/router';
import { GroupFormComponent } from './group-form.component';
import { Group, GroupsService } from '../../../../core/services/groups.service';

/**
 * Guards the group form's create/edit + payload-shape behaviour. Two
 * high-value invariants land here:
 *
 * <ol>
 *   <li>Edit mode surfaces the server-issued registration number
 *       read-only from findById() — the whole point of moving to
 *       auto-generation is that the operator can't rewrite it.</li>
 *   <li>The payload never carries a {@code registrationNumber} on
 *       either create or update, so a backend that stopped ignoring
 *       the field can't be silently exploited from a stale form.</li>
 * </ol>
 *
 * Direct-instantiation pattern (no TestBed) matches the charge-preview
 * + bad-debts + liaison-form specs — cheap and fast.
 */

function makeGroup(overrides: Partial<Group> = {}): Group {
  return {
    id: 'grp-1',
    name: 'Acme Ltd',
    registrationNumber: 'GRP-424242',
    address: '1 Main St',
    email: 'billing@acme.example',
    liaisonKind: 'MEMBER',
    liaisonUserId: 'mem-1',
    status: 'active',
    ...overrides,
  };
}

class StubActivatedRoute {
  private id: string | null = null;
  snapshot = { paramMap: { get: (_: string) => this.id } };
  withId(id: string | null): this { this.id = id; return this; }
}

describe('GroupFormComponent', () => {
  let groups: jasmine.SpyObj<GroupsService>;
  let router: jasmine.SpyObj<Router>;
  let route: StubActivatedRoute;
  let component: GroupFormComponent;

  beforeEach(() => {
    groups = jasmine.createSpyObj<GroupsService>('GroupsService', ['findById', 'create', 'update']);
    groups.create.and.returnValue(of(makeGroup()));
    groups.update.and.returnValue(of(makeGroup()));

    router = jasmine.createSpyObj<Router>('Router', ['navigate']);
    router.navigate.and.returnValue(Promise.resolve(true) as any);

    route = new StubActivatedRoute();

    component = new GroupFormComponent(
      groups,
      route as unknown as ActivatedRoute,
      router,
    );
  });

  // ------------------------------------------------------------------
  // Create mode — no id on the route → no findById call, no
  // registration number on the payload.
  // ------------------------------------------------------------------

  describe('create mode (no route id)', () => {
    beforeEach(() => {
      route.withId(null);
      component.ngOnInit();
    });

    it('does not call findById + leaves registrationNumber null', () => {
      expect(groups.findById).not.toHaveBeenCalled();
      expect(component.registrationNumber).toBeNull();
    });

    it('rejects submit when name is blank', () => {
      component.form = { name: '  ', email: '', address: '', liaisonKind: null, liaisonUserId: null };

      component.submit();

      expect(groups.create).not.toHaveBeenCalled();
      expect(component.errorMessage).toContain('Name');
    });

    it('rejects submit when neither liaison nor email is present', () => {
      component.form = { name: 'Acme', email: '', address: '', liaisonKind: null, liaisonUserId: null };

      component.submit();

      expect(groups.create).not.toHaveBeenCalled();
      expect(component.errorMessage).toContain('liaison');
      expect(component.errorMessage).toContain('email');
    });

    it('accepts submit with only an email (no liaison)', () => {
      component.form = {
        name: 'Acme', email: 'billing@acme.example', address: '',
        liaisonKind: null, liaisonUserId: null,
      };

      component.submit();

      expect(groups.create).toHaveBeenCalled();
      expect(component.errorMessage).toBeNull();
    });

    it('accepts submit with only a liaison (no email)', () => {
      component.form = {
        name: 'Acme', email: '', address: '',
        liaisonKind: 'MEMBER', liaisonUserId: 'mem-1',
      };

      component.submit();

      expect(groups.create).toHaveBeenCalled();
    });

    it('omits registrationNumber from the create payload', () => {
      // The whole point of moving to server-issued numbers: a stale
      // form draft with a `registrationNumber` should never sneak
      // one onto the wire. Guard the payload shape explicitly.
      component.form = {
        name: 'Acme', email: 'billing@acme.example', address: '  1 Main St  ',
        liaisonKind: null, liaisonUserId: null,
      };

      component.submit();

      const payload = groups.create.calls.mostRecent().args[0];
      expect(payload.name).toBe('Acme');
      expect(payload.address).toBe('1 Main St');
      // Payload must not carry a registrationNumber key at all.
      expect('registrationNumber' in payload).toBeFalse();
    });

    it('navigates back to /tenant/billing/groups on success', () => {
      component.form = {
        name: 'Acme', email: 'billing@acme.example', address: '',
        liaisonKind: null, liaisonUserId: null,
      };

      component.submit();

      expect(router.navigate).toHaveBeenCalledWith(['/tenant/billing/groups']);
      expect(component.saving).toBeFalse();
    });

    it('surfaces error detail without navigating away', () => {
      groups.create.and.returnValue(throwError(() => ({ error: { detail: 'Duplicate name' } })));
      component.form = {
        name: 'Acme', email: 'billing@acme.example', address: '',
        liaisonKind: null, liaisonUserId: null,
      };

      component.submit();

      expect(component.errorMessage).toBe('Duplicate name');
      expect(component.saving).toBeFalse();
      expect(router.navigate).not.toHaveBeenCalled();
    });
  });

  // ------------------------------------------------------------------
  // Edit mode — id on the route → findById populates the read-only
  // registration number pill.
  // ------------------------------------------------------------------

  describe('edit mode (route id present)', () => {
    beforeEach(() => {
      route.withId('grp-1');
      groups.findById.and.returnValue(of(makeGroup({ registrationNumber: 'GRP-424242' })));
      component.ngOnInit();
    });

    it('calls findById and surfaces registrationNumber read-only', () => {
      expect(groups.findById).toHaveBeenCalledWith('grp-1');
      expect(component.registrationNumber).toBe('GRP-424242');
    });

    it('populates the form from the loaded group without registrationNumber', () => {
      // The form object itself must NOT hold the registration number
      // — that would let a stale ngModel binding accidentally
      // resend it on save.
      expect(component.form.name).toBe('Acme Ltd');
      expect(component.form.email).toBe('billing@acme.example');
      expect('registrationNumber' in (component.form as any)).toBeFalse();
    });

    it('omits registrationNumber from the update payload', () => {
      component.form.name = 'Acme Renamed';

      component.submit();

      const payload = groups.update.calls.mostRecent().args[1];
      expect(payload.name).toBe('Acme Renamed');
      expect('registrationNumber' in payload).toBeFalse();
    });

    it('allows submit without a liaison + email in edit mode (backend guards)', () => {
      // In edit mode the pre-existing group already satisfies the
      // either-or rule server-side; the client-side gate only fires
      // on create. Regression here would block operators from
      // cosmetic edits (name change only) on a group whose reachable
      // state hasn't changed.
      component.form = {
        name: 'Acme Renamed', email: '', address: '',
        liaisonKind: null, liaisonUserId: null,
      };

      component.submit();

      expect(groups.update).toHaveBeenCalled();
      expect(component.errorMessage).toBeNull();
    });

    it('surfaces load-time errors from findById', () => {
      route.withId('grp-1');
      groups.findById.and.returnValue(throwError(() => ({ error: { detail: 'Not found' } })));

      component.ngOnInit();

      expect(component.errorMessage).toBe('Not found');
      expect(component.loading).toBeFalse();
    });
  });

  // ------------------------------------------------------------------
  // Liaison selection callback — drives the form's liaisonKind /
  // liaisonUserId and the picker's prefill props via
  // liaisonLabel/liaisonSublabel.
  // ------------------------------------------------------------------

  describe('onLiaisonSelected', () => {
    beforeEach(() => {
      route.withId(null);
      component.ngOnInit();
    });

    it('applies the selection to form + prefill state', () => {
      component.onLiaisonSelected({
        kind: 'STAFF', id: 'staff-1',
        label: 'Alice Smith', sublabel: 'alice@org',
      });

      expect(component.form.liaisonKind).toBe('STAFF');
      expect(component.form.liaisonUserId).toBe('staff-1');
      expect(component.liaisonLabel).toBe('Alice Smith');
      expect(component.liaisonSublabel).toBe('alice@org');
    });

    it('clears everything when the picker emits null', () => {
      component.form.liaisonKind = 'STAFF';
      component.form.liaisonUserId = 'staff-1';
      component.liaisonLabel = 'Alice';

      component.onLiaisonSelected(null);

      expect(component.form.liaisonKind).toBeNull();
      expect(component.form.liaisonUserId).toBeNull();
      expect(component.liaisonLabel).toBeNull();
      expect(component.liaisonSublabel).toBeNull();
    });
  });
});
