import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { DataTableComponent, TableAction } from '../../shared/components/data-table/data-table.component';
import { StatCardComponent } from '../../shared/components/stat-card/stat-card.component';
import { IconComponent } from '../../shared/components/icon/icon.component';
import { ContributionsService, Scheme } from '../../core/services/contributions.service';

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
  ];

  constructor(
    private contribService: ContributionsService,
    private router: Router,
  ) {}

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
