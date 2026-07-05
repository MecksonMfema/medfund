import { CommonModule } from '@angular/common';
import {
  Component, EventEmitter, Input, OnChanges, OnInit, Output, SimpleChanges,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Observable, Subject, debounceTime, distinctUntilChanged, of, switchMap } from 'rxjs';
import { AdminService, StaffUser } from '../../../core/services/admin.service';
import { GroupLiaison, GroupLiaisonsService } from '../../../core/services/group-liaisons.service';
import { Member, MembersService } from '../../../core/services/members.service';
import { TenantService } from '../../../core/services/tenant.service';
import { ToastService } from '../toast/toast.service';

export type LiaisonKind = 'MEMBER' | 'STAFF' | 'LIAISON';

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

interface NewLiaisonForm {
  firstName: string;
  lastName: string;
  email: string;
  phone: string;
  address: string;
}

const EMPTY_LIAISON: NewLiaisonForm = { firstName: '', lastName: '', email: '', phone: '', address: '' };

/**
 * Group-liaison picker. Search-only for MEMBER and STAFF — those
 * entities live in their own admin sections. For the LIAISON kind
 * (a standalone Keycloak account with the {@code group_liaison} role)
 * the picker exposes an inline collapsible mini-form so an operator
 * mid-way through a group create doesn't lose their draft when they
 * need to invite a fresh liaison contact.
 *
 * <p>The parent supplies prefill props (kind + id + label) for edit
 * forms; the component reflects them on first paint without doing its
 * own lookup-by-id.
 */
@Component({
  selector: 'app-liaison-picker',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
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
  newLiaison: NewLiaisonForm = { ...EMPTY_LIAISON };

  private query$ = new Subject<string>();

  constructor(
    private membersService: MembersService,
    private adminService: AdminService,
    private liaisonsService: GroupLiaisonsService,
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
    // Collapse the inline add-new form on a tab switch — it only
    // applies to LIAISON, so leaving it open under MEMBER / STAFF
    // would be confusing.
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

  // ── Inline "add new liaison" (LIAISON kind only) ────────────────────────

  openAddNew(): void {
    this.showAddNew = true;
    this.newLiaison = { ...EMPTY_LIAISON };
  }

  cancelAddNew(): void {
    this.showAddNew = false;
  }

  /**
   * Invite a fresh standalone liaison. Creates a Keycloak account in
   * the tenant's realm with the {@code group_liaison} role and sends
   * an invite email so the person can set their password. On success
   * the picker auto-selects the new liaison so the parent form
   * receives the {@link LiaisonSelection} event without any extra
   * clicks — the operator's draft group data is preserved.
   */
  submitNew(): void {
    const first = this.newLiaison.firstName.trim();
    const last  = this.newLiaison.lastName.trim();
    const email = this.newLiaison.email.trim();
    if (!first || !last || !email) {
      this.toast.error('First name, last name and email are required.');
      return;
    }
    this.saving = true;
    this.liaisonsService.create({
      firstName: first,
      lastName:  last,
      email,
      phone:   this.newLiaison.phone.trim() || undefined,
      address: this.newLiaison.address.trim() || undefined,
    }).subscribe({
      next: (l: GroupLiaison) => {
        this.saving = false;
        this.showAddNew = false;
        this.pick({
          id: l.id,
          label: `${l.firstName} ${l.lastName}`.trim(),
          sublabel: l.email,
        });
        this.toast.success(`Liaison ${l.firstName} ${l.lastName} invited by email.`);
      },
      error: (err) => {
        this.saving = false;
        this.toast.error(err?.error?.detail || err?.error?.title || 'Failed to create liaison');
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
    if (this.activeKind === 'STAFF') {
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
    return this.liaisonsService.search(term).pipe(
      switchMap(rows => of<Suggestion[]>(
        rows.map((l: GroupLiaison) => ({
          id: l.id,
          label: `${l.firstName} ${l.lastName}`.trim(),
          sublabel: l.email,
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
