import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import {
  ClaimsConfigService,
  TariffModifier,
} from '../../../../core/services/claims-config.service';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { HumanizePipe } from '../../../../shared/pipes/humanize.pipe';

@Component({
  selector: 'app-modifiers-list',
  standalone: true,
  imports: [CommonModule, SkeletonComponent, HumanizePipe],
  templateUrl: './modifiers-list.component.html',
  styleUrl: './modifiers-list.component.scss',
})
export class ModifiersListComponent implements OnInit {
  rows: TariffModifier[] = [];
  loading = false;
  errorMessage: string | null = null;

  constructor(private config: ClaimsConfigService) {}

  ngOnInit(): void {
    this.loading = true;
    this.config.listModifiers().subscribe({
      next: (rows) => { this.rows = rows; this.loading = false; },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load modifiers';
        this.loading = false;
      },
    });
  }

  formatAdjustment(m: TariffModifier): string {
    const v = m.adjustmentValue;
    switch (m.adjustmentType) {
      case 'PERCENTAGE': return `${v}%`;
      case 'FIXED':      return v;
      case 'MULTIPLIER': return `×${v}`;
      default:           return v;
    }
  }
}
