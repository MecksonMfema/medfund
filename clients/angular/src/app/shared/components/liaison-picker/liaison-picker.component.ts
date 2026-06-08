import { CommonModule } from '@angular/common';
import {
  Component, EventEmitter, Input, OnChanges, OnInit, Output, SimpleChanges,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Observable, Subject, debounceTime, distinctUntilChanged, of, switchMap } from 'rxjs';
import { AdminService, StaffUser } from '../../../core/services/admin.service';
import { Member, MembersService } from '../../../core/services/members.service';
import { TenantService } from '../../../core/services/tenant.service';
import { ToastService } from '../toast/toast.service';

export type LiaisonKind = 'MEMBER' | 'STAFF';

export interface LiaisonSelection {
  kind: LiaisonKind;
  id: string;
  label: string;
  sublabel?: string;
}

interface Suggestion {
  id: string;
  label: string;
  sublabel?: string;
}

interface NewMemberForm {
  firstName: string;
  lastName: string;
  dateOfBirth: string;
  email: string;
  phone: string;
}

interface NewStaffForm {
  firstName: string;
  lastName: string;
  email: string;
  phone: string;
  realmRole: string;
}

const EMPTY_MEMBER: NewMemberForm = { firstName: '', lastName: '', dateOfBirth: '', email: '', phone: '' };
const EMPTY_STAFF: NewStaffForm = { firstName: '', lastName: '', email: '', phone: '', realmRole: 'operations' };

/**
 * Group-liaison picker. The liaison can be (a) an existing member,
 * (b) an existing tenant staff user, or (c) a brand-new person of either
 * kind. The component owns the kind toggle, the debounced search, and the
 * inline "add new" mini-form so the parent only sees a single
 * {@link LiaisonSelection} payload.
 *
 * The parent supplies prefill props (kind + id + label) for edit forms;
 * the component reflects them on first paint without doing its own
 * lookup-by-id.
 */
@Component({
  selector: 'app-liaison-picker',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './liaison-picker.component.html',
  styleUrl: './liaison-picker.component.scss',
})
export class LiaisonPickerComponent implements OnInit, OnChanges {
  @Input() value: string | null = null;
  @Input() kind: LiaisonKind | null = null;
  @Input() prefillLabel: string | null = null;
  @Input() prefillSublabel: string | null = null;
  @Output() valueChange = new EventEmitter<string | null>();
  @Output() kindChange = new EventEmitter<LiaisonKind | null>();
  @Output() selected = new EventEmitter<LiaisonSelection | null>();

  activeKind: LiaisonKind = 'MEMBER';
  query = '';
  matches: Suggestion[] = [];
  searching = false;
  picked: Suggestion | null = null;
  showMatches = false;

  showAddNew = false;
  saving = false;
  newMember: NewMemberForm = { ...EMPTY_MEMBER };
  newStaff: NewStaffForm = { ...EMPTY_STAFF };

  /** Realm role options for new-staff creation. Mirrors RoleService seeds. */
  readonly STAFF_ROLES = [
    { value: 'operations',      label: 'Operations Staff' },
    { value: 'tenant_admin',    label: 'Tenant Administrator' },
    { value: 'finance_officer', label: 'Finance Officer' },
    { value: 'claims_officer',  label: 'Claims Officer' },
  ];

  private query$ = new Subject<string>();

  constructor(
    private membersService: MembersService,
    private adminService: AdminService,
    private tenantService: TenantService,
    private toast: ToastService,
  ) {}

  ngOnInit(): void {
    if (this.kind) this.activeKind = this.kind;

    this.query$
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        switchMap((q) => {
          const trimmed = q.trim();
          if (!trimmed) {
            this.searching = false;
            return of<Suggestion[]>([]);
          }
          this.searching = true;
          return this.search(trimmed);
        }),
      )
      .subscribe({
        next: (rows) => { this.matches = rows; this.searching = false; },
        error: () => { this.matches = []; this.searching = false; },
      });
  }

  ngOnChanges(changes: SimpleChanges): void {
    const valueChanged = 'value' in changes;
    const labelChanged = 'prefillLabel' in changes;
    if ((valueChanged || labelChanged) && this.value && this.prefillLabel) {
      this.picked = {
        id: this.value,
        label: this.prefillLabel,
        sublabel: this.prefillSublabel ?? undefined,
      };
      this.query = this.prefillLabel;
      if (this.kind) this.activeKind = this.kind;
    }
    if (valueChanged && !this.value) {
      this.picked = null;
      this.query = '';
    }
  }

  // ── Tabs ────────────────────────────────────────────────────────────────

  setKind(k: LiaisonKind): void {
    if (this.activeKind === k) return;
    this.activeKind = k;
    this.matches = [];
    this.query = '';
    this.showMatches = false;
    this.showAddNew = false;
  }

  // ── Search / selection ──────────────────────────────────────────────────

  onQueryChange(): void {
    this.showMatches = true;
    this.query$.next(this.query);
  }

  pick(s: Suggestion): void {
    this.picked = s;
    this.query = s.label;
    this.matches = [];
    this.showMatches = false;
    this.value = s.id;
    this.kind = this.activeKind;
    this.emit();
  }

  clear(): void {
    this.picked = null;
    this.query = '';
    this.matches = [];
    this.value = null;
    this.kind = null;
    this.emit(null);
  }

  onBlur(): void {
    setTimeout(() => { this.showMatches = false; }, 150);
  }

  // ── Inline "add new" ────────────────────────────────────────────────────

  openAddNew(): void {
    this.showAddNew = true;
    this.newMember = { ...EMPTY_MEMBER };
    this.newStaff = { ...EMPTY_STAFF };
  }

  cancelAddNew(): void {
    this.showAddNew = false;
  }

  submitNew(): void {
    if (this.activeKind === 'MEMBER') {
      if (!this.newMember.firstName.trim() || !this.newMember.lastName.trim() || !this.newMember.dateOfBirth) {
        this.toast.error('First name, last name and date of birth are required.');
        return;
      }
      this.saving = true;
      this.membersService.enroll({
        firstName: this.newMember.firstName.trim(),
        lastName:  this.newMember.lastName.trim(),
        dateOfBirth: this.newMember.dateOfBirth,
        email: this.newMember.email.trim() || undefined,
        phone: this.newMember.phone.trim() || undefined,
      }).subscribe({
        next: (m: Member) => {
          this.saving = false;
          this.showAddNew = false;
          this.pick({
            id: m.id,
            label: `${m.firstName} ${m.lastName}`.trim(),
            sublabel: m.memberNumber,
          });
        },
        error: (err) => {
          this.saving = false;
          this.toast.error(err?.error?.detail || 'Enrolment failed');
        },
      });
      return;
    }

    // STAFF
    if (!this.newStaff.firstName.trim() || !this.newStaff.lastName.trim() || !this.newStaff.email.trim()) {
      this.toast.error('First name, last name and email are required.');
      return;
    }
    this.saving = true;
    const tenantId = this.tenantService.getTenantId() || null;
    this.adminService.createStaffUser({
      firstName: this.newStaff.firstName.trim(),
      lastName:  this.newStaff.lastName.trim(),
      email:     this.newStaff.email.trim(),
      jobTitle:  'Group Liaison',
      realmRole: this.newStaff.realmRole,
      tenantId,
    }).subscribe({
      next: (s: StaffUser) => {
        this.saving = false;
        this.showAddNew = false;
        this.pick({
          id: s.id,
          label: `${s.firstName} ${s.lastName}`.trim(),
          sublabel: s.email,
        });
      },
      error: (err) => {
        this.saving = false;
        this.toast.error(err?.error?.detail || 'Failed to create staff user');
      },
    });
  }

  // ── Internals ───────────────────────────────────────────────────────────

  private search(term: string): Observable<Suggestion[]> {
    if (this.activeKind === 'MEMBER') {
      return this.membersService.searchByName(term).pipe(
        switchMap(rows => of<Suggestion[]>(
          rows.map((m: Member) => ({
            id: m.id,
            label: `${m.firstName} ${m.lastName}`.trim(),
            sublabel: m.memberNumber,
          })),
        )),
      );
    }
    const tenantId = this.tenantService.getTenantId() || undefined;
    return this.adminService.searchStaffUsers(term, tenantId).pipe(
      switchMap(rows => of<Suggestion[]>(
        rows.map((s: StaffUser) => ({
          id: s.id,
          label: `${s.firstName} ${s.lastName}`.trim(),
          sublabel: s.email,
        })),
      )),
    );
  }

  private emit(override?: null): void {
    if (override === null) {
      this.valueChange.emit(null);
      this.kindChange.emit(null);
      this.selected.emit(null);
      return;
    }
    this.valueChange.emit(this.value);
    this.kindChange.emit(this.kind);
    if (this.picked && this.kind) {
      this.selected.emit({
        kind: this.kind,
        id: this.picked.id,
        label: this.picked.label,
        sublabel: this.picked.sublabel,
      });
    } else {
      this.selected.emit(null);
    }
  }
}
