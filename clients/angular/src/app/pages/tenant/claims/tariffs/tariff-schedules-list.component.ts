import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import {
  ClaimsConfigService,
  TariffSchedule,
} from '../../../../core/services/claims-config.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { DataTableComponent, TableAction, TableColumn } from '../../../../shared/components/data-table/data-table.component';

@Component({
  selector: 'app-tariff-schedules-list',
  standalone: true,
  imports: [CommonModule, RouterLink, IconComponent, DataTableComponent],
  templateUrl: './tariff-schedules-list.component.html',
  styleUrl: './tariff-schedules-list.component.scss',
})
export class TariffSchedulesListComponent implements OnInit {
  rows: TariffSchedule[] = [];
  loading = false;
  errorMessage: string | null = null;
  pageSize = 20;
  sortKey = 'effectiveDate';
  sortDirection: 'asc' | 'desc' = 'desc';

  readonly columns: TableColumn[] = [
    { key: 'name',          label: 'Name',           sortable: true },
    { key: 'source',        label: 'Source',         sortable: true },
    { key: 'effectiveDate', label: 'Effective from', sortable: true },
    { key: 'endDate',       label: 'End date',       sortable: true },
    { key: 'status',        label: 'Status',         sortable: true, type: 'status' },
  ];

  readonly actions: TableAction[] = [
    {
      label: 'View codes',
      icon: 'eye',
      color: 'default',
      handler: (row: TariffSchedule) => this.router.navigate(['/tenant/claims/tariffs', row.id, 'codes']),
    },
  ];

  constructor(private config: ClaimsConfigService, private router: Router) {}

  ngOnInit(): void { this.refresh(); }

  refresh(): void {
    this.loading = true;
    this.config.listSchedules().subscribe({
      next: (rows) => { this.rows = rows; this.loading = false; },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load tariff schedules';
        this.rows = [];
        this.loading = false;
      },
    });
  }
}
