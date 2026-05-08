import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ContributionsService, AgeGroup, Scheme } from '../../../../core/services/contributions.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { CurrencyFormatPipe } from '../../../../shared/pipes/currency-format.pipe';

interface AgeGroupRow extends AgeGroup {
  schemeName?: string;
}

@Component({
  selector: 'app-age-groups-list',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent, SkeletonComponent, CurrencyFormatPipe],
  templateUrl: './age-groups-list.component.html',
  styleUrl: './age-groups-list.component.scss',
})
export class AgeGroupsListComponent implements OnInit {
  schemes: Scheme[] = [];
  selectedSchemeId: string | null = null;
  rows: AgeGroupRow[] = [];
  loading = false;
  errorMessage: string | null = null;

  constructor(private contributions: ContributionsService) {}

  ngOnInit(): void {
    this.loading = true;
    this.contributions.getSchemes().subscribe({
      next: (schemes) => {
        this.schemes = schemes;
        if (schemes.length > 0) {
          this.selectedSchemeId = schemes[0].id;
          this.loadAgeGroups(schemes[0].id);
        } else {
          this.loading = false;
        }
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load schemes';
        this.loading = false;
      },
    });
  }

  loadAgeGroups(schemeId: string): void {
    if (!schemeId) return;
    this.loading = true;
    this.contributions.getAgeGroupsByScheme(schemeId).subscribe({
      next: (rows) => {
        const schemeName = this.schemes.find(s => s.id === schemeId)?.name;
        this.rows = rows.map(r => ({ ...r, schemeName }));
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load age groups';
        this.loading = false;
      },
    });
  }

  onSchemeChange(): void {
    if (this.selectedSchemeId) this.loadAgeGroups(this.selectedSchemeId);
  }

  selectedScheme(): Scheme | undefined {
    return this.schemes.find(s => s.id === this.selectedSchemeId);
  }
}
