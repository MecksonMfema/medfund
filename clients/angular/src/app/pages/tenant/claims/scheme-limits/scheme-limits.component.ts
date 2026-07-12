import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { catchError, forkJoin, of } from 'rxjs';
import {
  ContributionsService,
  Scheme,
  SchemeBenefit,
} from '../../../../core/services/contributions.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { ToastService } from '../../../../shared/components/toast/toast.service';
import { CurrencyFormatPipe } from '../../../../shared/pipes/currency-format.pipe';
import { HumanizePipe } from '../../../../shared/pipes/humanize.pipe';

interface SchemeBundle {
  scheme: Scheme;
  benefits: SchemeBenefit[];
}

@Component({
  selector: 'app-scheme-limits',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    RouterLink,
    IconComponent,
    SelectComponent,
    SkeletonComponent,
    CurrencyFormatPipe,
    HumanizePipe,
  ],
  templateUrl: './scheme-limits.component.html',
  styleUrl: './scheme-limits.component.scss',
})
export class SchemeLimitsComponent implements OnInit {
  bundles: SchemeBundle[] = [];
  loading = false;
  schemeFilter = '';
  q = '';

  constructor(
    private contributions: ContributionsService,
    private toast: ToastService,
  ) {}

  ngOnInit(): void { this.refresh(); }

  refresh(): void {
    this.loading = true;
    this.contributions.getSchemes().subscribe({
      next: (schemes) => {
        // Only surface health-line schemes here — benefit limits are meaningful
        // for HEALTH; VEHICLE / LIFE / FUNERAL etc. are surfaced elsewhere.
        const health = schemes.filter(s => !s.insuranceLine || s.insuranceLine === 'HEALTH');
        if (health.length === 0) { this.bundles = []; this.loading = false; return; }

        forkJoin(
          health.map(s =>
            this.contributions.getBenefitsByScheme(s.id).pipe(catchError(() => of([] as SchemeBenefit[]))),
          ),
        ).subscribe({
          next: (benefitLists) => {
            this.bundles = health.map((scheme, i) => ({ scheme, benefits: benefitLists[i] || [] }));
            this.loading = false;
          },
          error: () => { this.loading = false; },
        });
      },
      error: (err) => {
        this.loading = false;
        this.toast.error(err?.error?.detail || 'Failed to load schemes');
      },
    });
  }

  get schemeOptions(): SelectOption[] {
    return [
      { value: '', label: 'All schemes' },
      ...this.bundles.map(b => ({ value: b.scheme.id, label: b.scheme.name })),
    ];
  }

  get visible(): SchemeBundle[] {
    let list = this.bundles;
    if (this.schemeFilter) list = list.filter(b => b.scheme.id === this.schemeFilter);
    if (this.q.trim()) {
      const q = this.q.trim().toLowerCase();
      list = list
        .map(b => ({
          scheme: b.scheme,
          benefits: b.benefits.filter(x =>
            x.name.toLowerCase().includes(q) ||
            x.benefitType.toLowerCase().includes(q),
          ),
        }))
        .filter(b => b.benefits.length > 0);
    }
    return list;
  }

  benefitLimit(b: SchemeBenefit): string {
    // Preferred order: annual > event > daily. The row shows all three columns
    // regardless — this helper is just for the "primary" cap chip on empty
    // states / summaries.
    return b.annualLimit || b.eventLimit || b.dailyLimit || 'Unlimited';
  }

  totalBenefits(): number {
    return this.bundles.reduce((s, b) => s + b.benefits.length, 0);
  }
}
