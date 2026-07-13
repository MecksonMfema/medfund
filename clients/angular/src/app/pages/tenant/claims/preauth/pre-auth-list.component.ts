import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import {
  PreAuthorization,
  PreAuthService,
} from '../../../../core/services/pre-auth.service';
import { DataTableComponent, TableAction, TableColumn } from '../../../../shared/components/data-table/data-table.component';

interface StatusTab {
  /** Lowercase status value, or null for "All". */
  value: string | null;
  label: string;
}

@Component({
  selector: 'app-pre-auth-list',
  standalone: true,
  imports: [CommonModule, DataTableComponent],
  templateUrl: './pre-auth-list.component.html',
  styleUrl: './pre-auth-list.component.scss',
})
export class PreAuthListComponent implements OnInit {
  rows: PreAuthorization[] = [];
  filtered: PreAuthorization[] = [];
  loading = false;
  errorMessage: string | null = null;
  pageSize = 20;
  sortKey = 'createdAt';
  sortDirection: 'asc' | 'desc' = 'desc';

  readonly statusTabs: StatusTab[] = [
    { value: null,       label: 'All'      },
    { value: 'pending',  label: 'Pending'  },
    { value: 'approved', label: 'Approved' },
    { value: 'rejected', label: 'Rejected' },
    { value: 'expired',  label: 'Expired'  },
  ];
  activeStatus: string | null = null;

  readonly columns: TableColumn[] = [
    { key: 'authNumber',       label: 'Auth #',    sortable: true },
    { key: 'tariffCode',       label: 'Tariff',    sortable: true },
    { key: 'diagnosisCode',    label: 'Diagnosis', sortable: true },
    { key: 'requestedAmount',  label: 'Requested', sortable: true, type: 'currency' },
    { key: 'approvedAmount',   label: 'Approved',  sortable: true, type: 'currency' },
    { key: 'status',           label: 'Status',    sortable: true, type: 'status' },
    { key: 'expiryDate',       label: 'Expires',   sortable: true },
    { key: 'createdAt',        label: 'Submitted', sortable: true, type: 'date' },
  ];

  readonly actions: TableAction[] = [
    {
      label: 'View',
      icon: 'eye',
      color: 'default',
      handler: (row: PreAuthorization) => this.router.navigate(['/tenant/claims/preauth', row.id]),
    },
  ];

  constructor(private service: PreAuthService, private router: Router) {}

  ngOnInit(): void { this.refresh(); }

  refresh(): void {
    this.loading = true;
    this.service.list().subscribe({
      next: (rows) => { this.rows = rows; this.applyFilter(); this.loading = false; },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load pre-authorizations';
        this.rows = [];
        this.applyFilter();
        this.loading = false;
      },
    });
  }

  selectStatus(value: string | null): void {
    if (this.activeStatus === value) return;
    this.activeStatus = value;
    this.applyFilter();
  }

  private applyFilter(): void {
    if (!this.activeStatus) { this.filtered = this.rows; return; }
    this.filtered = this.rows.filter(r => r.status === this.activeStatus);
  }
}
