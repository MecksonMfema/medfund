import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import {
  ClaimsConfigService,
  CreateTariffSchedulePayload,
} from '../../../../core/services/claims-config.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { endOfMonth, firstOfMonth } from '../../../../shared/utils/date-snap';

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

  /** feedback_effective_date_snap: start dates ride the 1st of the month. */
  onEffectiveDateBlur(): void {
    this.form.effectiveDate = firstOfMonth(this.form.effectiveDate);
  }

  /** feedback_effective_date_snap: end dates ride the last day of the month
   *  so the tariff stays valid through the whole cycle it ends in. */
  onEndDateBlur(): void {
    this.form.endDate = endOfMonth(this.form.endDate);
  }

  submit(): void {
    if (!this.form.name.trim() || !this.form.effectiveDate) {
      this.errorMessage = 'Name and effective date are required';
      return;
    }
    // Belt-and-braces snap at submit — in case the operator typed instead of
    // blurring out of the input.
    const payload: CreateTariffSchedulePayload = {
      name: this.form.name.trim(),
      effectiveDate: firstOfMonth(this.form.effectiveDate),
      endDate: this.form.endDate ? endOfMonth(this.form.endDate) : undefined,
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
