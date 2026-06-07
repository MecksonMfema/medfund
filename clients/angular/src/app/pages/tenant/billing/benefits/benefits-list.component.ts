import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import {
  ContributionsService,
  Scheme,
  SchemeBenefit,
} from '../../../../core/services/contributions.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { CurrencyFormatPipe } from '../../../../shared/pipes/currency-format.pipe';
import { HumanizePipe } from '../../../../shared/pipes/humanize.pipe';
import { ToastService } from '../../../../shared/components/toast/toast.service';

@Component({
  selector: 'app-benefits-list',
  standalone: true,
  imports: [CommonModule, RouterLink, IconComponent, SkeletonComponent, CurrencyFormatPipe, HumanizePipe],
  templateUrl: './benefits-list.component.html',
  styleUrl: './benefits-list.component.scss',
})
export class BenefitsListComponent implements OnInit {
  schemeId = '';
  scheme: Scheme | null = null;
  benefits: SchemeBenefit[] = [];
  loading = false;
  errorMessage: string | null = null;
  pendingId: string | null = null;

  constructor(
    private contributions: ContributionsService,
    private route: ActivatedRoute,
    private router: Router,
    private toast: ToastService,
  ) {}

  ngOnInit(): void {
    this.schemeId = this.route.snapshot.paramMap.get('schemeId') ?? '';
    if (!this.schemeId) {
      this.errorMessage = 'No scheme id in route';
      return;
    }
    this.refresh();
  }

  refresh(): void {
    this.loading = true;
    this.errorMessage = null;
    forkJoin({
      scheme: this.contributions.getSchemeById(this.schemeId),
      benefits: this.contributions.getBenefitsByScheme(this.schemeId),
    }).subscribe({
      next: ({ scheme, benefits }) => {
        this.scheme = scheme;
        this.benefits = benefits;
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load benefits';
        this.loading = false;
      },
    });
  }

  edit(b: SchemeBenefit): void {
    this.router.navigate(['/tenant/billing/schemes', this.schemeId, 'benefits', b.id, 'edit']);
  }

  deactivate(b: SchemeBenefit): void {
    if (!confirm(`Deactivate benefit "${b.name}"? It will stay on file for historical claims but won't be picked up for new ones.`)) return;
    this.pendingId = b.id;
    this.contributions.deactivateBenefit(b.id).subscribe({
      next: (updated) => {
        this.benefits = this.benefits.map(x => x.id === b.id ? { ...x, status: updated.status } : x);
        this.pendingId = null;
        this.toast.success(`"${b.name}" deactivated`);
      },
      error: (err) => {
        this.pendingId = null;
        this.toast.error(err?.error?.detail || 'Could not deactivate benefit');
      },
    });
  }
}
