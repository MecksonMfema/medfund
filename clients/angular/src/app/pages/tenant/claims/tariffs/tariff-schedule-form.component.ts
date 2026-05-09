import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import {
  ClaimsConfigService,
  CreateTariffSchedulePayload,
} from '../../../../core/services/claims-config.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';

@Component({
  selector: 'app-tariff-schedule-form',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent],
  templateUrl: './tariff-schedule-form.component.html',
  styleUrl: './tariff-schedule-form.component.scss',
})
export class TariffScheduleFormComponent {
  saving = false;
  errorMessage: string | null = null;

  form: CreateTariffSchedulePayload = {
    name: '',
    effectiveDate: new Date().toISOString().slice(0, 10),
    endDate: '',
    source: '',
  };

  constructor(private config: ClaimsConfigService, private router: Router) {}

  submit(): void {
    if (!this.form.name.trim() || !this.form.effectiveDate) {
      this.errorMessage = 'Name and effective date are required';
      return;
    }
    const payload: CreateTariffSchedulePayload = {
      name: this.form.name.trim(),
      effectiveDate: this.form.effectiveDate,
      endDate: this.form.endDate || undefined,
      source: this.form.source?.trim() || undefined,
    };
    this.saving = true;
    this.errorMessage = null;
    this.config.createSchedule(payload).subscribe({
      next: (saved) => {
        this.saving = false;
        // Land on the new schedule's codes page so the user can start adding.
        this.router.navigate(['/tenant/claims/tariffs', saved.id, 'codes']);
      },
      error: (err) => {
        this.saving = false;
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Save failed';
      },
    });
  }
}
