import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { ContributionsService, Scheme, UpsertAgeGroupPayload } from '../../../../core/services/contributions.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';

@Component({
  selector: 'app-age-group-form',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent],
  templateUrl: './age-group-form.component.html',
  styleUrl: './age-group-form.component.scss',
})
export class AgeGroupFormComponent implements OnInit {
  schemes: Scheme[] = [];
  saving = false;
  errorMessage: string | null = null;

  form = {
    schemeId: '',
    name: '',
    minAge: 0,
    maxAge: 18,
    contributionAmount: '',
  };

  constructor(private contributions: ContributionsService, private router: Router) {}

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
    this.contributions.createAgeGroup(payload).subscribe({
      next: () => {
        this.saving = false;
        this.router.navigate(['/tenant/billing/age-groups']);
      },
      error: (err) => {
        this.saving = false;
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Save failed';
      },
    });
  }
}
