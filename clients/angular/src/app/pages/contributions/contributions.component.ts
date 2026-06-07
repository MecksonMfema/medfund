import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { DataTableComponent, TableAction } from '../../shared/components/data-table/data-table.component';
import { StatCardComponent } from '../../shared/components/stat-card/stat-card.component';
import { IconComponent } from '../../shared/components/icon/icon.component';
import { ContributionsService, Scheme } from '../../core/services/contributions.service';
import { ToastService } from '../../shared/components/toast/toast.service';

@Component({
  selector: 'app-contributions',
  standalone: true,
  imports: [CommonModule, RouterLink, DataTableComponent, StatCardComponent, IconComponent],
  templateUrl: './contributions.component.html',
  styleUrl: './contributions.component.scss',
})
export class ContributionsComponent implements OnInit {
  schemes: Scheme[] = [];
  schemeCount = 0;
  pendingCount = 0;
  paidCount = 0;
  overdueCount = 0;

  schemeColumns = [
    { key: 'name',          label: 'Scheme Name' },
    { key: 'schemeType',    label: 'Type', type: 'label' },
    { key: 'currencyCode',  label: 'Currency' },
    { key: 'status',        label: 'Status', type: 'status' },
    { key: 'effectiveDate', label: 'Effective Date', type: 'date' },
  ];

  schemeActions: TableAction[] = [
    {
      label: 'Benefits',
      icon: 'clipboard-list',
      color: 'default',
      handler: (row: Scheme) => this.router.navigate(['/tenant/billing/schemes', row.id, 'benefits']),
    },
    {
      label: 'Edit',
      icon: 'edit',
      color: 'default',
      handler: (row: Scheme) => this.router.navigate(['/tenant/billing/schemes', row.id, 'edit']),
    },
    {
      // Label, icon, and color flip per row based on current status — one
      // action slot that always shows, never a row with no toggle option.
      label: 'Toggle',
      icon: 'pause-circle',
      color: 'default',
      labelFor: (row: Scheme) => row.status === 'inactive' ? 'Activate' : 'Deactivate',
      iconFor:  (row: Scheme) => row.status === 'inactive' ? 'play-circle' : 'pause-circle',
      colorFor: (row: Scheme) => row.status === 'inactive' ? 'success' : 'danger',
      handler: (row: Scheme) => this.toggleSchemeStatus(row),
    },
  ];

  constructor(
    private contribService: ContributionsService,
    private router: Router,
    private toast: ToastService,
  ) {}

  private toggleSchemeStatus(row: Scheme): void {
    const wantsActive = row.status === 'inactive';
    const verb = wantsActive ? 'Activate' : 'Deactivate';
    const note = wantsActive
      ? `Activate scheme "${row.name}"? New contributions will start generating again.`
      : `Deactivate scheme "${row.name}"? Existing contributions, benefits, and claims stay on file but new ones won't be generated.`;
    if (!confirm(note)) return;
    const stream = wantsActive
      ? this.contribService.activateScheme(row.id)
      : this.contribService.deactivateScheme(row.id);
    stream.subscribe({
      next: (updated) => {
        this.toast.success(`"${row.name}" ${updated.status === 'active' ? 'activated' : 'deactivated'}`);
        this.schemes = this.schemes.map(s => s.id === row.id ? { ...s, status: updated.status } : s);
      },
      error: (err) => {
        this.toast.error(err?.error?.detail || `Could not ${verb.toLowerCase()} scheme`);
      },
    });
  }

  ngOnInit(): void {
    this.contribService.getSchemes().subscribe({
      next: (schemes) => {
        this.schemes = schemes;
        this.schemeCount = schemes.filter(s => s.status === 'active').length;
      },
      error: () => {},
    });
  }
}
