import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import {
  ClaimsConfigService,
  RejectionReason,
  UpsertRejectionReasonPayload,
} from '../../../../core/services/claims-config.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';

@Component({
  selector: 'app-rejection-reason-form',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent, SelectComponent],
  templateUrl: './rejection-reason-form.component.html',
  styleUrl: './rejection-reason-form.component.scss',
})
export class RejectionReasonFormComponent implements OnInit {
  reasonId: string | null = null;
  loading = false;
  saving = false;
  errorMessage: string | null = null;

  // Categories track those used by the adjudication pipeline. Tenants can
  // pick "Other" to introduce a new bucket that surfaces in the catalogue
  // without being treated specially by the rules engine.
  readonly categories = [
    'ELIGIBILITY', 'WAITING_PERIOD', 'BENEFIT', 'PREAUTH',
    'TARIFF', 'CLINICAL', 'FRAUD', 'OTHER',
  ];

  form: UpsertRejectionReasonPayload = {
    code: '',
    description: '',
    category: '',
    isActive: true,
  };

  get categoryOptions(): SelectOption[] {
    return [
      { value: '', label: '— None —' },
      ...this.categories.map(c => ({ value: c, label: c })),
    ];
  }

  constructor(
    private config: ClaimsConfigService,
    private route: ActivatedRoute,
    private router: Router,
  ) {}

  ngOnInit(): void {
    this.reasonId = this.route.snapshot.paramMap.get('id');
    if (!this.reasonId) return;
    this.loading = true;
    // No GET-by-id endpoint; load the full list and pluck the one we need.
    // The catalogue is small (R01..R18 + tenant additions) so this is fine.
    this.config.listRejectionReasons(false).subscribe({
      next: (rows) => {
        const found = rows.find(r => r.id === this.reasonId);
        if (found) {
          this.form = {
            code: found.code,
            description: found.description,
            category: found.category ?? '',
            isActive: found.isActive,
          };
        } else {
          this.errorMessage = 'Rejection reason not found';
        }
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load reason';
        this.loading = false;
      },
    });
  }

  submit(): void {
    if (!this.form.code.trim() || !this.form.description.trim()) {
      this.errorMessage = 'Code and description are required';
      return;
    }
    const payload: UpsertRejectionReasonPayload = {
      code: this.form.code.trim().toUpperCase(),
      description: this.form.description.trim(),
      category: this.form.category?.trim() || undefined,
      isActive: this.form.isActive,
    };
    this.saving = true;
    this.errorMessage = null;
    const stream = this.reasonId
      ? this.config.updateRejectionReason(this.reasonId, payload)
      : this.config.createRejectionReason(payload);
    stream.subscribe({
      next: () => {
        this.saving = false;
        this.router.navigate(['/tenant/claims/rejection-reasons']);
      },
      error: (err) => {
        this.saving = false;
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Save failed';
      },
    });
  }
}
