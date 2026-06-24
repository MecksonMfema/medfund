import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { ContributionsService, AgeGroup } from '../../../../core/services/contributions.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { CurrencyFormatPipe } from '../../../../shared/pipes/currency-format.pipe';
import { ToastService } from '../../../../shared/components/toast/toast.service';
import {
  EntityPickerComponent,
  EntityPickerSelection,
} from '../../../../shared/components/entity-picker/entity-picker.component';

interface AgeGroupRow extends AgeGroup {
  schemeName?: string;
}

@Component({
  selector: 'app-age-groups-list',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    RouterLink,
    IconComponent,
    SkeletonComponent,
    CurrencyFormatPipe,
    EntityPickerComponent,
  ],
  templateUrl: './age-groups-list.component.html',
  styleUrl: './age-groups-list.component.scss',
})
export class AgeGroupsListComponent {
  selectedSchemeId: string | null = null;
  selectedSchemeName: string | null = null;
  rows: AgeGroupRow[] = [];
  loading = false;
  errorMessage: string | null = null;

  constructor(
    private contributions: ContributionsService,
    private router: Router,
    private toast: ToastService,
  ) {}

  edit(row: AgeGroupRow): void {
    this.router.navigate(['/tenant/billing/age-groups', row.id, 'edit']);
  }

  toggleStatus(row: AgeGroupRow): void {
    const wantsActive = row.status === 'inactive';
    const note = wantsActive
      ? `Activate age group "${row.name}"? It will be used for new contributions again.`
      : `Deactivate age group "${row.name}"? It will stay on file but won't be used for new contributions.`;
    if (!confirm(note)) return;
    const stream = wantsActive
      ? this.contributions.activateAgeGroup(row.id)
      : this.contributions.deactivateAgeGroup(row.id);
    stream.subscribe({
      next: (updated) => {
        this.toast.success(`"${row.name}" ${updated.status === 'active' ? 'activated' : 'deactivated'}`);
        const idx = this.rows.findIndex(r => r.id === row.id);
        if (idx >= 0) this.rows[idx] = { ...this.rows[idx], status: updated.status };
      },
      error: (err) => {
        this.toast.error(err?.error?.detail || `Could not ${wantsActive ? 'activate' : 'deactivate'} age group`);
      },
    });
  }

  onSchemePicked(selection: EntityPickerSelection | null): void {
    this.selectedSchemeId = selection?.id ?? null;
    this.selectedSchemeName = selection?.label ?? null;
    this.rows = [];
    if (selection?.id) {
      this.loadAgeGroups(selection.id, selection.label);
    }
  }

  private loadAgeGroups(schemeId: string, schemeName: string): void {
    this.loading = true;
    this.contributions.getAgeGroupsByScheme(schemeId).subscribe({
      next: (rows) => {
        this.rows = rows.map(r => ({ ...r, schemeName }));
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load age groups';
        this.loading = false;
      },
    });
  }
}
