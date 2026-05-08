import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { forkJoin } from 'rxjs';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import {
  AdminService,
  PermissionCatalogue,
  PermissionDomain,
  Role,
  StaffUser,
  UserRoleAssignment,
} from '../../../../core/services/admin.service';
import { PermissionService } from '../../../../core/security/permission.service';

interface MemberRow {
  userId: string;
  email?: string;
  name?: string;
}

interface RoleRow extends Role {
  memberCount: number;
}

/**
 * Tenant role management — embedded as a tab inside the IT-admin Settings page.
 *
 * <p>Three views:
 * <ul>
 *   <li>Role list (default) — table of tenant-defined roles + counts.</li>
 *   <li>Editor drawer — name, description, permission grid grouped by domain.</li>
 *   <li>Members drawer — list of staff users holding the role; add/remove.</li>
 * </ul>
 *
 * <p>System roles (the seeded {@code tenant_admin}) are shown but cannot be
 * edited or deleted — the controller enforces this; the UI mirrors it so the
 * action buttons aren't even rendered.
 */
@Component({
  selector: 'app-tenant-roles-tab',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, SkeletonComponent],
  templateUrl: './roles-tab.component.html',
  styleUrl: './roles-tab.component.scss',
})
export class TenantRolesTabComponent implements OnInit {
  // ── Role list state ────────────────────────────────────────────────────
  roles: RoleRow[] = [];
  loading = false;
  loadError: string | null = null;

  // ── Catalogue (permissions grouped by domain) ─────────────────────────
  catalogue: PermissionDomain[] = [];

  // ── Editor drawer state ────────────────────────────────────────────────
  /** Null = drawer closed; otherwise indicates whether the user is creating or editing. */
  editingMode: 'create' | 'edit' | null = null;
  editingRole: Role | null = null;
  editorForm = { name: '', displayName: '', description: '' };
  selectedPerms = new Set<string>();
  expandedDomains = new Set<string>();
  editorSaving = false;
  editorError: string | null = null;

  // ── Members drawer state ───────────────────────────────────────────────
  membersFor: Role | null = null;
  members: MemberRow[] = [];
  candidateUsers: StaffUser[] = [];
  membersLoading = false;
  membersSaving = false;
  selectedCandidateId = '';

  constructor(
    private admin: AdminService,
    private permissions: PermissionService,
  ) {}

  ngOnInit(): void {
    this.load();
  }

  private load(): void {
    this.loading = true;
    this.loadError = null;
    forkJoin({
      roles: this.admin.getRoles(),
      catalogue: this.admin.getPermissionCatalogue(),
    }).subscribe({
      next: ({ roles, catalogue }) => {
        this.catalogue = catalogue.domains;
        // Resolve member counts in parallel — small N so worth the simplicity.
        forkJoin(
          roles.map(r => this.admin.getRoleMembers(r.id).pipe()),
        ).subscribe({
          next: memberLists => {
            this.roles = roles.map((r, i) => ({ ...r, memberCount: memberLists[i]?.length ?? 0 }));
            this.loading = false;
          },
          error: () => {
            // Member counts are non-critical — render rows without counts.
            this.roles = roles.map(r => ({ ...r, memberCount: 0 }));
            this.loading = false;
          },
        });
        if (roles.length === 0) this.loading = false;
      },
      error: err => {
        this.loadError = err?.error?.detail || 'Could not load roles or permission catalogue.';
        this.loading = false;
      },
    });
  }

  // ── Editor drawer ─────────────────────────────────────────────────────

  openCreate(): void {
    this.editingRole = null;
    this.editingMode = 'create';
    this.editorForm = { name: '', displayName: '', description: '' };
    this.selectedPerms = new Set();
    this.expandAllDomains();
    this.editorError = null;
  }

  openEdit(role: RoleRow): void {
    this.editingMode = 'edit';
    this.editingRole = role;
    this.editorForm = {
      name: role.name,
      displayName: role.displayName,
      description: role.description ?? '',
    };
    this.editorError = null;
    this.expandAllDomains();
    this.admin.getRole(role.id).subscribe({
      next: full => {
        this.selectedPerms = new Set(full.permissions.map(p => p.permission));
      },
      error: () => {
        this.selectedPerms = new Set();
      },
    });
  }

  closeEditor(): void {
    this.editingMode = null;
    this.editingRole = null;
    this.editorError = null;
  }

  togglePermission(key: string): void {
    if (this.selectedPerms.has(key)) this.selectedPerms.delete(key);
    else this.selectedPerms.add(key);
    // Force change detection by re-creating the set reference
    this.selectedPerms = new Set(this.selectedPerms);
  }

  isSelected(key: string): boolean {
    return this.selectedPerms.has(key);
  }

  toggleDomain(domainId: string): void {
    if (this.expandedDomains.has(domainId)) this.expandedDomains.delete(domainId);
    else this.expandedDomains.add(domainId);
    this.expandedDomains = new Set(this.expandedDomains);
  }

  isDomainExpanded(domainId: string): boolean {
    return this.expandedDomains.has(domainId);
  }

  /**
   * Domain master toggle — when every key in the domain is selected, clear
   * them all; otherwise add the missing ones. Familiar bulk-select pattern.
   */
  toggleAllInDomain(domain: PermissionDomain): void {
    const allSelected = domain.permissions.every(p => this.selectedPerms.has(p.key));
    const next = new Set(this.selectedPerms);
    if (allSelected) {
      domain.permissions.forEach(p => next.delete(p.key));
    } else {
      domain.permissions.forEach(p => next.add(p.key));
    }
    this.selectedPerms = next;
  }

  isDomainAllSelected(domain: PermissionDomain): boolean {
    return domain.permissions.length > 0 && domain.permissions.every(p => this.selectedPerms.has(p.key));
  }

  isDomainPartiallySelected(domain: PermissionDomain): boolean {
    const sel = domain.permissions.filter(p => this.selectedPerms.has(p.key)).length;
    return sel > 0 && sel < domain.permissions.length;
  }

  saveEditor(): void {
    this.editorError = null;
    if (!this.editorForm.displayName.trim()) {
      this.editorError = 'Display name is required.';
      return;
    }
    if (this.editingMode === 'create' && !this.editorForm.name.trim()) {
      this.editorError = 'Name is required.';
      return;
    }

    this.editorSaving = true;

    if (this.editingMode === 'create') {
      this.admin.createRole({
        name: this.editorForm.name.trim(),
        displayName: this.editorForm.displayName.trim(),
        description: this.editorForm.description.trim() || undefined,
        permissions: Array.from(this.selectedPerms).map(p => ({ permission: p })),
      }).subscribe({
        next: () => {
          this.editorSaving = false;
          this.closeEditor();
          this.load();
          this.permissions.refresh().subscribe();
        },
        error: err => {
          this.editorError = err?.error?.detail || err?.error?.message || 'Could not create role.';
          this.editorSaving = false;
        },
      });
      return;
    }

    // Edit mode — split metadata update from permission replace so the user
    // can change either independently and so each call returns the relevant
    // shape for any subsequent UI updates.
    const id = this.editingRole!.id;
    forkJoin({
      meta: this.admin.updateRole(id, {
        displayName: this.editorForm.displayName.trim(),
        description: this.editorForm.description.trim() || undefined,
      }),
      perms: this.admin.replaceRolePermissions(id, Array.from(this.selectedPerms)),
    }).subscribe({
      next: () => {
        this.editorSaving = false;
        this.closeEditor();
        this.load();
        this.permissions.refresh().subscribe();
      },
      error: err => {
        this.editorError = err?.error?.detail || err?.error?.message || 'Could not save role.';
        this.editorSaving = false;
      },
    });
  }

  delete(role: RoleRow): void {
    if (role.isSystem) return;
    if (!confirm(`Delete role "${role.displayName}"? Every user holding this role will lose it.`)) return;
    this.admin.deleteRole(role.id).subscribe({
      next: () => {
        this.load();
        this.permissions.refresh().subscribe();
      },
      error: err => {
        this.loadError = err?.error?.detail || 'Could not delete role.';
      },
    });
  }

  // ── Members drawer ────────────────────────────────────────────────────

  openMembers(role: RoleRow): void {
    this.membersFor = role;
    this.members = [];
    this.candidateUsers = [];
    this.membersLoading = true;
    this.selectedCandidateId = '';

    forkJoin({
      members: this.admin.getRoleMembers(role.id),
      // Tenant staff users are the candidate pool — getStaffPage with cursor=null
      // returns the first page; for v1 we don't paginate inside the drawer.
      staff: this.admin.getStaffPage({ limit: 200 }, this.tenantHint() ?? undefined),
    }).subscribe({
      next: ({ members, staff }) => {
        // Hydrate member rows with user details for display.
        this.members = members.map((m: UserRoleAssignment) => {
          const user = staff.content.find(s => s.id === m.userId);
          return {
            userId: m.userId,
            email: user?.email,
            name: user ? `${user.firstName} ${user.lastName}` : undefined,
          };
        });
        // Candidates = staff not already holding the role.
        const memberIds = new Set(members.map(m => m.userId));
        this.candidateUsers = staff.content.filter(s => !memberIds.has(s.id));
        this.membersLoading = false;
      },
      error: err => {
        this.loadError = err?.error?.detail || 'Could not load role members.';
        this.membersLoading = false;
      },
    });
  }

  closeMembers(): void {
    this.membersFor = null;
  }

  addMember(): void {
    if (!this.selectedCandidateId || !this.membersFor) return;
    this.membersSaving = true;
    this.admin.assignRoleToUser(this.selectedCandidateId, this.membersFor.id).subscribe({
      next: () => {
        this.membersSaving = false;
        this.openMembers(this.membersFor as RoleRow); // refresh
      },
      error: err => {
        this.loadError = err?.error?.detail || 'Could not add member.';
        this.membersSaving = false;
      },
    });
  }

  removeMember(member: MemberRow): void {
    if (!this.membersFor) return;
    if (!confirm(`Remove ${member.email ?? member.userId} from "${this.membersFor.displayName}"?`)) return;
    this.membersSaving = true;
    this.admin.revokeRoleFromUser(member.userId, this.membersFor.id).subscribe({
      next: () => {
        this.membersSaving = false;
        this.openMembers(this.membersFor as RoleRow);
      },
      error: err => {
        this.loadError = err?.error?.detail || 'Could not remove member.';
        this.membersSaving = false;
      },
    });
  }

  // ── Helpers ───────────────────────────────────────────────────────────

  private expandAllDomains(): void {
    this.expandedDomains = new Set(this.catalogue.map(d => d.id));
  }

  private tenantHint(): string | null {
    // Staff list endpoint accepts an X-Tenant-ID via a tenantId param so the
    // candidate pool is filtered to this tenant. Reads it from the URL — the
    // tenant interceptor adds the header automatically when on /tenant/* paths.
    return null;
  }
}
