import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import {
  Group,
  GroupsService,
  UpsertGroupPayload,
} from '../../../../core/services/groups.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';

@Component({
  selector: 'app-group-form',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent],
  templateUrl: './group-form.component.html',
  styleUrl: './group-form.component.scss',
})
export class GroupFormComponent implements OnInit {
  groupId: string | null = null;
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
  };

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
        this.form = {
          name: g.name,
          registrationNumber: g.registrationNumber ?? '',
          contactPerson: g.contactPerson ?? '',
          contactEmail: g.contactEmail ?? '',
          contactPhone: g.contactPhone ?? '',
          address: g.address ?? '',
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
    const payload: UpsertGroupPayload = {
      name: this.form.name.trim(),
      registrationNumber: this.form.registrationNumber?.trim() || undefined,
      contactPerson: this.form.contactPerson?.trim() || undefined,
      contactEmail: this.form.contactEmail?.trim() || undefined,
      contactPhone: this.form.contactPhone?.trim() || undefined,
      address: this.form.address?.trim() || undefined,
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
