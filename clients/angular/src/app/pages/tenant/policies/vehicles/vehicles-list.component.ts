import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import {
  PoliciesPageResponse,
  PoliciesService,
  VehicleRow,
} from '../../../../core/services/policies.service';
import {
  DataTableComponent,
  TableAction,
  TableColumn,
} from '../../../../shared/components/data-table/data-table.component';

@Component({
  selector: 'app-vehicles-list',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, DataTableComponent],
  templateUrl: './vehicles-list.component.html',
  styleUrl: './vehicles-list.component.scss',
})
export class VehiclesListComponent implements OnInit {
  rows: VehicleRow[] = [];
  loading = false;
  errorMessage: string | null = null;

  page = 1;
  pageSize = 50;
  totalCount = 0;
  totalPages = 1;
  sortKey = 'registrationNumber';
  sortDirection: 'asc' | 'desc' = 'asc';
  searchTerm = '';

  readonly columns: TableColumn[] = [
    { key: 'registrationNumber', label: 'Registration', sortable: true },
    { key: 'makeModel',          label: 'Make / Model', sortable: false },
    { key: 'year',               label: 'Year',         sortable: true },
    { key: 'vehicleValue',       label: 'Value',        sortable: true, type: 'currency' },
    { key: 'schemeName',         label: 'Scheme',       sortable: true },
    { key: 'ownerMemberName',    label: 'Owner',        sortable: true },
    { key: 'status',             label: 'Status',       sortable: true },
  ];

  readonly actions: TableAction[] = [
    {
      label: 'Open',
      icon: 'eye',
      color: 'default',
      handler: (row: VehicleRow) =>
        this.router.navigate(['/tenant/policies/vehicles', row.id]),
    },
  ];

  constructor(private policies: PoliciesService, private router: Router) {}

  ngOnInit(): void { this.fetchPage(); }

  fetchPage(): void {
    this.loading = true;
    this.policies.listVehiclesPaged({
      q: this.searchTerm || undefined,
      sortKey: this.sortKey,
      sortDirection: this.sortDirection,
      page: this.page - 1,
      size: this.pageSize,
    }).subscribe({
      next: (resp: PoliciesPageResponse<VehicleRow>) => {
        this.rows = (resp.content ?? []).map(r => ({
          ...r,
          makeModel: [r.make, r.model].filter(Boolean).join(' '),
        }) as VehicleRow & { makeModel: string });
        this.totalCount = resp.total;
        this.totalPages = resp.totalPages;
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load vehicles';
        this.rows = [];
        this.totalCount = 0;
        this.totalPages = 1;
        this.loading = false;
      },
    });
  }

  onPageChange(page: number): void { this.page = page; this.fetchPage(); }
  onSearchChange(term: string): void { this.searchTerm = term; this.page = 1; this.fetchPage(); }
  onSortChange(evt: { key: string; direction: 'asc' | 'desc' }): void {
    this.sortKey = evt.key;
    this.sortDirection = evt.direction;
    this.page = 1;
    this.fetchPage();
  }
}
