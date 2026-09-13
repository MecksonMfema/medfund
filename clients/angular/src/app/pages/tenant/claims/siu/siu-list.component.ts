import { CommonModule } from '@angular/common';
import { Component, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';
import { DataTableComponent, TableAction, TableColumn } from '../../../../shared/components/data-table/data-table.component';
import { ToastService } from '../../../../shared/components/toast/toast.service';
import { extractErrorMessage } from '../../../../core/util/http-errors';
import { SiuCaseSummary, SiuService } from './siu.service';

/**
 * SIU (Special Investigations Unit) case queue — Phase 19 §A Phase 6.
 * Row-click opens the case-detail page. §B Phase 7 adds a "New case"
 * button + assignedTo picker + "My cases" tab.
 */
@Component({
  selector: 'app-siu-list',
  standalone: true,
  imports: [CommonModule, FormsModule, SelectComponent, DataTableComponent],
  templateUrl: './siu-list.component.html',
})
export class SiuListComponent implements OnInit {
  rows: SiuCaseSummary[] = [];
  loading = false;

  statusFilter = '';

  readonly statusFilterOptions: SelectOption[] = [
    { value: '', label: 'All' },
    { value: 'OPEN', label: 'Open' },
    { value: 'UNDER_REVIEW', label: 'Under review' },
    { value: 'CLOSED_CONFIRMED_FRAUD', label: 'Closed: confirmed fraud' },
    { value: 'CLOSED_DISMISSED_FALSE_POSITIVE', label: 'Closed: dismissed' },
  ];

  readonly columns: TableColumn[] = [
    { key: 'caseNumber', label: 'Case #',    sortable: true },
    { key: 'status',     label: 'Status',    sortable: true, type: 'status' },
    { key: 'priority',   label: 'Priority',  sortable: true, type: 'label' },
    { key: 'flagCount',  label: 'Flags',     sortable: true },
    { key: 'openedAt',   label: 'Opened at', sortable: true, type: 'date' },
  ];

  readonly actions: TableAction[] = [
    {
      label: 'Open',
      icon: 'eye',
      color: 'default',
      handler: (row: SiuCaseSummary) =>
        this.router.navigate(['/tenant/claims/siu', row.id]),
    },
  ];

  constructor(private siu: SiuService, private router: Router, private toast: ToastService) {}

  ngOnInit(): void {
    this.fetch();
  }

  fetch(): void {
    this.loading = true;
    this.siu.list(this.statusFilter || undefined).subscribe({
      next: (rows) => {
        this.rows = rows;
        this.loading = false;
      },
      error: (err) => {
        this.toast.error(extractErrorMessage(err, 'Failed to load SIU cases'));
        this.rows = [];
        this.loading = false;
      },
    });
  }

  onStatusChange(): void {
    this.fetch();
  }
}
