import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import {
  ClaimsConfigService,
  TariffSchedule,
} from '../../../../core/services/claims-config.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { HumanizePipe } from '../../../../shared/pipes/humanize.pipe';

@Component({
  selector: 'app-tariff-schedules-list',
  standalone: true,
  imports: [CommonModule, RouterLink, IconComponent, SkeletonComponent, HumanizePipe],
  templateUrl: './tariff-schedules-list.component.html',
  styleUrl: './tariff-schedules-list.component.scss',
})
export class TariffSchedulesListComponent implements OnInit {
  rows: TariffSchedule[] = [];
  loading = false;
  errorMessage: string | null = null;

  constructor(private config: ClaimsConfigService, private router: Router) {}

  ngOnInit(): void {
    this.loading = true;
    this.config.listSchedules().subscribe({
      next: (rows) => { this.rows = rows; this.loading = false; },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load tariff schedules';
        this.loading = false;
      },
    });
  }

  openCodes(s: TariffSchedule): void {
    this.router.navigate(['/tenant/claims/tariffs', s.id, 'codes']);
  }
}
