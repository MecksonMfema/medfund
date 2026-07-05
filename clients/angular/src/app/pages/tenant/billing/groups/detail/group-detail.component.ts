import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Group, GroupsService, LiaisonKind, UpsertGroupPayload } from '../../../../../core/services/groups.service';
import { Member, MembersService } from '../../../../../core/services/members.service';
import { AdminService } from '../../../../../core/services/admin.service';
import { GroupLiaisonsService } from '../../../../../core/services/group-liaisons.service';
import { TenantService } from '../../../../../core/services/tenant.service';
import { IconComponent } from '../../../../../shared/components/icon/icon.component';
import { LiaisonPickerComponent, LiaisonSelection } from '../../../../../shared/components/liaison-picker/liaison-picker.component';
import { ToastService } from '../../../../../shared/components/toast/toast.service';

@Component({
  selector: 'app-group-detail',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent, LiaisonPickerComponent],
  templateUrl: './group-detail.component.html',
  styleUrl: './group-detail.component.scss',
})
export class GroupDetailComponent implements OnInit {
  groupId = '';
  group: Group | null = null;
  loading = false;
  saving = false;
  errorMessage: string | null = null;

  form: UpsertGroupPayload = {
    name: '',
    registrationNumber: '',
    address: '',
    email: '',
    liaisonKind: null,
    liaisonUserId: null,
  };

  liaisonLabel: string | null = null;
  liaisonSublabel: string | null = null;

  members: Member[] = [];
  membersLoading = false;

  constructor(
    private groups: GroupsService,
    private memberService: MembersService,
    private adminService: AdminService,
    private liaisonsService: GroupLiaisonsService,
    private tenantService: TenantService,
    private route: ActivatedRoute,
    private router: Router,
    private toast: ToastService,
  ) {}

  ngOnInit(): void {
    this.groupId = this.route.snapshot.paramMap.get('id') ?? '';
    if (!this.groupId) {
      this.errorMessage = 'Group id missing from the URL.';
      return;
    }
    this.loadGroup();
    this.loadMembers();
  }

  private loadGroup(): void {
    this.loading = true;
    this.groups.findById(this.groupId).subscribe({
      next: (g) => {
        this.group = g;
        this.form = {
          name: g.name,
          registrationNumber: g.registrationNumber ?? '',
          address: g.address ?? '',
          email: g.email ?? '',
          liaisonKind: g.liaisonKind ?? null,
          liaisonUserId: g.liaisonUserId ?? null,
        };
        this.loading = false;
        this.loadLiaisonLabel(g);
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load group';
        this.loading = false;
      },
    });
  }

  private loadMembers(): void {
    this.membersLoading = true;
    this.memberService.getByGroupId(this.groupId).subscribe({
      next: (list) => { this.members = list; this.membersLoading = false; },
      error: (err) => {
        this.toast.error(err?.error?.detail || 'Failed to load group members');
        this.membersLoading = false;
      },
    });
  }

  save(): void {
    if (!this.form.name?.trim()) {
      this.errorMessage = 'Name is required';
      return;
    }
    // Either-or rule: the group needs a route for statements — a liaison
    // OR a contact email. Both is fine; clearing both is not.
    const hasLiaison = !!(this.form.liaisonKind && this.form.liaisonKind !== 'CLEAR' && this.form.liaisonUserId);
    const hasEmail = !!this.form.email?.trim();
    if (!hasLiaison && !hasEmail) {
      this.errorMessage = 'Add either a liaison or a contact email — the group needs at least one route for contribution statements.';
      return;
    }
    this.saving = true;
    this.errorMessage = null;
    const payload: UpsertGroupPayload = {
      name: this.form.name.trim(),
      registrationNumber: this.form.registrationNumber?.trim() || undefined,
      address:            this.form.address?.trim()            || undefined,
      email:              this.form.email?.trim() || undefined,
      liaisonKind:        this.form.liaisonKind                ?? undefined,
      liaisonUserId:      this.form.liaisonUserId              ?? undefined,
    };
    this.groups.update(this.groupId, payload).subscribe({
      next: (updated) => {
        this.group = updated;
        this.saving = false;
        this.toast.success('Group updated');
      },
      error: (err) => {
        const msg = err?.error?.detail || err?.error?.title || 'Save failed';
        this.errorMessage = msg;
        this.toast.error(msg);
        this.saving = false;
      },
    });
  }

  suspend(): void {
    if (!this.group || this.group.status === 'SUSPENDED') return;
    if (!confirm(`Suspend group "${this.group.name}"?`)) return;
    this.groups.suspend(this.groupId).subscribe({
      next: (updated) => { this.group = updated; this.toast.success('Group suspended'); },
      error: (err) => this.toast.error(err?.error?.detail || 'Suspend failed'),
    });
  }

  /**
   * Group terminate = deactivate. Cascades to members on the backend.
   * Effective-date prompt matches the dependant deactivate flow:
   * Cancel = abort, empty = today, valid ISO = that date. Reason is
   * captured too so the audit event has context.
   */
  canTerminate(): boolean {
    return !!this.group
      && this.group.status !== 'TERMINATED'
      && this.group.status !== 'DEACTIVATED';
  }

  terminate(): void {
    if (!this.group || !this.canTerminate()) return;
    const today = new Date().toISOString().slice(0, 10);
    const dateAnswer = prompt(
      `Terminate group "${this.group.name}".\n\n` +
      `Effective date (YYYY-MM-DD). Members are cascaded on the same run; empty = today.`,
      today,
    );
    if (dateAnswer === null) return; // Cancel
    const effectiveDate = dateAnswer.trim() || today;
    if (!/^\d{4}-\d{2}-\d{2}$/.test(effectiveDate)) {
      this.toast.error(`Invalid date "${effectiveDate}". Use YYYY-MM-DD.`);
      return;
    }
    const reasonAnswer = prompt(
      `Reason for terminating "${this.group?.name}" (optional).`,
      '',
    );
    const reason = reasonAnswer?.trim() || null;
    this.groups.terminate(this.groupId, effectiveDate, reason).subscribe({
      next: (updated) => {
        this.group = updated;
        this.toast.success(`Group terminated effective ${effectiveDate}`);
      },
      error: (err) => this.toast.error(err?.error?.detail || 'Terminate failed'),
    });
  }

  openMember(m: Member): void {
    if (m?.id) this.router.navigate(['/tenant/members', m.id]);
  }

  onLiaisonSelected(s: LiaisonSelection | null): void {
    if (s) {
      this.form.liaisonKind = s.kind as LiaisonKind;
      this.form.liaisonUserId = s.id;
      this.liaisonLabel = s.label;
      this.liaisonSublabel = s.sublabel ?? null;
    } else {
      // Operator cleared the picker — send 'CLEAR' so the backend nullifies
      // both columns under the schema CHECK constraint.
      this.form.liaisonKind = 'CLEAR';
      this.form.liaisonUserId = null;
      this.liaisonLabel = null;
      this.liaisonSublabel = null;
    }
  }

  /** Resolve the saved liaisonUserId to a display label so the picker chip
   *  paints with the entity name instead of blank on first paint. */
  private loadLiaisonLabel(g: Group): void {
    if (!g.liaisonKind || !g.liaisonUserId) {
      this.liaisonLabel = null;
      this.liaisonSublabel = null;
      return;
    }
    if (g.liaisonKind === 'MEMBER') {
      this.memberService.getById(g.liaisonUserId).subscribe({
        next: (m) => {
          this.liaisonLabel = `${m.firstName} ${m.lastName}`.trim();
          this.liaisonSublabel = m.memberNumber ?? null;
        },
        error: () => { this.liaisonLabel = null; this.liaisonSublabel = null; },
      });
    } else if (g.liaisonKind === 'STAFF') {
      const tenantId = this.tenantService.getTenantId() || undefined;
      this.adminService.getStaffUserById(g.liaisonUserId, tenantId).subscribe({
        next: (s) => {
          this.liaisonLabel = `${s.firstName} ${s.lastName}`.trim();
          this.liaisonSublabel = s.email ?? null;
        },
        error: () => { this.liaisonLabel = null; this.liaisonSublabel = null; },
      });
    } else if (g.liaisonKind === 'LIAISON') {
      this.liaisonsService.getById(g.liaisonUserId).subscribe({
        next: (l) => {
          this.liaisonLabel = `${l.firstName} ${l.lastName}`.trim();
          this.liaisonSublabel = l.email ?? null;
        },
        error: () => { this.liaisonLabel = null; this.liaisonSublabel = null; },
      });
    }
  }

  back(): void { this.router.navigate(['/tenant/billing/groups']); }
}
