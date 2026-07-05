import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import {
  Group,
  GroupsService,
  LiaisonKind,
  UpsertGroupPayload,
} from '../../../../core/services/groups.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { LiaisonPickerComponent, LiaisonSelection } from '../../../../shared/components/liaison-picker/liaison-picker.component';

@Component({
  selector: 'app-group-form',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent, LiaisonPickerComponent],
  templateUrl: './group-form.component.html',
  styleUrl: './group-form.component.scss',
})
export class GroupFormComponent implements OnInit {
  groupId: string | null = null;
  loading = false;
  saving = false;
  errorMessage: string | null = null;
  /**
   * Server-issued registration number surfaced read-only on the edit
   * form. On the create form this stays null until save; the server
   * mints the value per the tenant's configured shape (V125).
   */
  registrationNumber: string | null = null;

  form: UpsertGroupPayload = {
    name: '',
    address: '',
    email: '',
    liaisonKind: null,
    liaisonUserId: null,
  };

  /** Set when the user picks (or creates) a liaison via the picker; lets the
   *  picker re-render with the chip on subsequent change-detection cycles. */
  liaisonLabel: string | null = null;
  liaisonSublabel: string | null = null;

  onLiaisonSelected(s: LiaisonSelection | null): void {
    if (s) {
      this.form.liaisonKind = s.kind as LiaisonKind;
      this.form.liaisonUserId = s.id;
      this.liaisonLabel = s.label;
      this.liaisonSublabel = s.sublabel ?? null;
    } else {
      this.form.liaisonKind = null;
      this.form.liaisonUserId = null;
      this.liaisonLabel = null;
      this.liaisonSublabel = null;
    }
  }

  constructor(
    private groups: GroupsService,
    private route: ActivatedRoute,
    private router: Router,
  ) {}

  ngOnInit(): void {
    this.groupId = this.route.snapshot.paramMap.get('id');
    if (!this.groupId) return;
    this.loading = true;
    this.groups.findById(this.groupId).subscribe({
      next: (g: Group) => {
        this.registrationNumber = g.registrationNumber ?? null;
        this.form = {
          name: g.name,
          address: g.address ?? '',
          email: g.email ?? '',
          liaisonKind: g.liaisonKind ?? null,
          liaisonUserId: g.liaisonUserId ?? null,
        };
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load group';
        this.loading = false;
      },
    });
  }

  submit(): void {
    if (!this.form.name.trim()) {
      this.errorMessage = 'Name is required';
      return;
    }
    // Either-or rule: the group needs at least one route for statements —
    // a liaison user OR a contact email. Both is fine; neither is not.
    const hasLiaison = !!(this.form.liaisonKind && this.form.liaisonKind !== 'CLEAR' && this.form.liaisonUserId);
    const hasEmail = !!this.form.email?.trim();
    if (!this.groupId && !hasLiaison && !hasEmail) {
      this.errorMessage = 'Add either a liaison or a contact email — the group needs at least one route for contribution statements.';
      return;
    }
    const payload: UpsertGroupPayload = {
      name: this.form.name.trim(),
      // registrationNumber deliberately omitted — the backend issues it
      // per the tenant's configured shape (V125) and ignores any value
      // in the payload. Leaving it off the wire keeps the intent clear.
      address: this.form.address?.trim() || undefined,
      email:         this.form.email?.trim() || undefined,
      liaisonKind:   this.form.liaisonKind   ?? undefined,
      liaisonUserId: this.form.liaisonUserId ?? undefined,
    };
    this.saving = true;
    this.errorMessage = null;
    const stream = this.groupId
      ? this.groups.update(this.groupId, payload)
      : this.groups.create(payload);
    stream.subscribe({
      next: () => {
        this.saving = false;
        this.router.navigate(['/tenant/billing/groups']);
      },
      error: (err) => {
        this.saving = false;
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Save failed';
      },
    });
  }
}
