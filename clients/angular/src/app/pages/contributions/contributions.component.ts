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
      label: 'Deactivate',
      icon: 'pause-circle',
      color: 'danger',
      // Hide once already inactive so the user can't keep clicking it.
      visible: (row: Scheme) => row.status !== 'inactive',
      handler: (row: Scheme) => this.deactivateScheme(row),
    },
  ];

  constructor(
    private contribService: ContributionsService,
    private router: Router,
    private toast: ToastService,
  ) {}

  private deactivateScheme(row: Scheme): void {
    if (!confirm(`Deactivate scheme "${row.name}"? Existing contributions, benefits, and claims stay on file but new ones won't be generated.`)) return;
    this.contribService.deactivateScheme(row.id).subscribe({
      next: (updated) => {
        this.toast.success(`"${row.name}" deactivated`);
        this.schemes = this.schemes.map(s => s.id === row.id ? { ...s, status: updated.status } : s);
      },
      error: (err) => {
        this.toast.error(err?.error?.detail || 'Could not deactivate scheme');
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
