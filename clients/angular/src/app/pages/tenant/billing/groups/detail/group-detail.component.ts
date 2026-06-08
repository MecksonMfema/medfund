import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Group, GroupsService, LiaisonKind, UpsertGroupPayload } from '../../../../../core/services/groups.service';
import { Member, MembersService } from '../../../../../core/services/members.service';
import { AdminService } from '../../../../../core/services/admin.service';
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
    contactPerson: '',
    contactEmail: '',
    contactPhone: '',
    address: '',
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
          contactPerson: g.contactPerson ?? '',
          contactEmail: g.contactEmail ?? '',
          contactPhone: g.contactPhone ?? '',
          address: g.address ?? '',
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
    this.saving = true;
    this.errorMessage = null;
    const payload: UpsertGroupPayload = {
      name: this.form.name.trim(),
      registrationNumber: this.form.registrationNumber?.trim() || undefined,
      contactPerson:      this.form.contactPerson?.trim()      || undefined,
      contactEmail:       this.form.contactEmail?.trim()       || undefined,
      contactPhone:       this.form.contactPhone?.trim()       || undefined,
      address:            this.form.address?.trim()            || undefined,
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
    }
  }

  back(): void { this.router.navigate(['/tenant/billing/groups']); }
}
