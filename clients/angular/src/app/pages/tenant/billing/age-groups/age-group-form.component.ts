import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ContributionsService, Scheme, UpsertAgeGroupPayload } from '../../../../core/services/contributions.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';
import { ToastService } from '../../../../shared/components/toast/toast.service';

interface AgeGroupForm {
  schemeId: string;
  name: string;
  minAge: number;
  maxAge: number;
  contributionAmount: string;
}

@Component({
  selector: 'app-age-group-form',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent, SelectComponent],
  templateUrl: './age-group-form.component.html',
  styleUrl: './age-group-form.component.scss',
})
export class AgeGroupFormComponent implements OnInit {
  ageGroupId: string | null = null;
  schemes: Scheme[] = [];
  loading = false;
  saving = false;
  errorMessage: string | null = null;

  form: AgeGroupForm = {
    schemeId: '',
    name: '',
    minAge: 0,
    maxAge: 18,
    contributionAmount: '',
  };

  /** Snapshot taken after an existing age group loads — drives the
   *  dirty-disabled state of the Save button. */
  private originalForm: AgeGroupForm | null = null;

  get isDirty(): boolean {
    if (!this.originalForm) return true;
    const f = this.form, o = this.originalForm;
    return (
      f.schemeId           !== o.schemeId ||
      f.name               !== o.name ||
      f.minAge             !== o.minAge ||
      f.maxAge             !== o.maxAge ||
      f.contributionAmount !== o.contributionAmount
    );
  }

  get schemeOptions(): SelectOption[] {
    return this.schemes.map(s => ({
      value: s.id,
      label: `${s.name} (${s.currencyCode || '—'})`,
    }));
  }

  constructor(
    private contributions: ContributionsService,
    private route: ActivatedRoute,
    private router: Router,
    private toast: ToastService,
  ) {}

  ngOnInit(): void {
    this.contributions.getSchemes().subscribe({
      next: (schemes) => {
        this.schemes = schemes;
        if (schemes.length > 0 && !this.form.schemeId) {
          this.form.schemeId = schemes[0].id;
        }
      },
      error: (err) => { this.errorMessage = err?.error?.detail || 'Failed to load schemes'; },
    });

    this.ageGroupId = this.route.snapshot.paramMap.get('id');
    if (this.ageGroupId) {
      this.loading = true;
      this.contributions.getAgeGroupById(this.ageGroupId).subscribe({
        next: (g) => {
          this.form = {
            schemeId: g.schemeId,
            name: g.name,
            minAge: g.minAge,
            maxAge: g.maxAge,
            contributionAmount: g.contributionAmount,
          };
          this.originalForm = { ...this.form };
          this.loading = false;
        },
        error: (err) => {
          this.errorMessage = err?.error?.detail || 'Failed to load age group';
          this.loading = false;
        },
      });
    }
  }

  selectedScheme(): Scheme | undefined {
    return this.schemes.find(s => s.id === this.form.schemeId);
  }

  submit(): void {
    if (!this.form.schemeId || !this.form.name.trim() || !this.form.contributionAmount) {
      this.errorMessage = 'Pick a scheme, a name, and an amount';
      return;
    }
    if (this.form.minAge > this.form.maxAge) {
      this.errorMessage = 'Min age cannot exceed max age';
      return;
    }
    const payload: UpsertAgeGroupPayload = {
      schemeId: this.form.schemeId,
      name: this.form.name.trim(),
      minAge: this.form.minAge,
      maxAge: this.form.maxAge,
      contributionAmount: this.form.contributionAmount,
    };
    this.saving = true;
    this.errorMessage = null;
    const isEdit = !!this.ageGroupId;
    const stream = isEdit
      ? this.contributions.updateAgeGroup(this.ageGroupId!, payload)
      : this.contributions.createAgeGroup(payload);
    stream.subscribe({
      next: () => {
        this.saving = false;
        this.toast.success(isEdit ? 'Age group updated' : 'Age group created');
        this.router.navigate(['/tenant/billing/age-groups']);
      },
      error: (err) => {
        this.saving = false;
        const detail = err?.error?.detail || err?.error?.title || 'Save failed';
        this.errorMessage = detail;
        this.toast.error(detail);
      },
    });
  }
}
