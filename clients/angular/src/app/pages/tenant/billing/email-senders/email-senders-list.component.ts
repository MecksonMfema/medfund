import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import {
  EmailSender,
  EmailSendersPageResponse,
  EmailSendersService,
} from '../../../../core/services/email-senders.service';
import {
  DataTableComponent,
  TableAction,
  TableColumn,
} from '../../../../shared/components/data-table/data-table.component';

@Component({
  selector: 'app-email-senders-list',
  standalone: true,
  imports: [CommonModule, RouterLink, DataTableComponent],
  templateUrl: './email-senders-list.component.html',
  styleUrl: './email-senders-list.component.scss',
})
export class EmailSendersListComponent implements OnInit {
  rows: EmailSender[] = [];
  loading = false;
  errorMessage: string | null = null;
  pendingId: string | null = null;

  page = 1;
  pageSize = 50;
  totalCount = 0;
  totalPages = 1;
  sortKey = 'address';
  sortDirection: 'asc' | 'desc' = 'asc';
  searchTerm = '';

  readonly columns: TableColumn[] = [
    { key: 'address',     label: 'Address',      sortable: true },
    { key: 'displayName', label: 'Display name', sortable: true },
    { key: 'status',      label: 'Status',       sortable: true },
    { key: 'verifiedAt',  label: 'Verified',     sortable: true, type: 'date' },
    { key: 'notes',       label: 'Notes',        sortable: false },
  ];

  readonly actions: TableAction[] = [
    {
      label: 'Edit',
      icon: 'edit',
      color: 'default',
      handler: (row: EmailSender) => this.edit(row),
    },
    {
      label: 'Verify',
      icon: 'check',
      color: 'primary',
      handler: (row: EmailSender) => this.verify(row),
      visible: (row: EmailSender) => row.status === 'pending',
    },
    {
      label: 'Revoke',
      icon: 'x',
      color: 'danger',
      handler: (row: EmailSender) => this.revoke(row),
      visible: (row: EmailSender) => row.status !== 'revoked',
    },
  ];

  constructor(private senders: EmailSendersService, private router: Router) {}

  ngOnInit(): void { this.fetchPage(); }

  fetchPage(): void {
    this.loading = true;
    this.senders.listPaged({
      q: this.searchTerm || undefined,
      sortKey: this.sortKey,
      sortDirection: this.sortDirection,
      page: this.page - 1,
      size: this.pageSize,
    }).subscribe({
      next: (resp: EmailSendersPageResponse<EmailSender>) => {
        this.rows = resp.content ?? [];
        this.totalCount = resp.total;
        this.totalPages = resp.totalPages;
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load email senders';
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

  edit(s: EmailSender): void {
    this.router.navigate(['/tenant/billing/email-senders', s.id, 'edit']);
  }

  verify(s: EmailSender): void {
    if (!confirm(`Mark ${s.address} as verified? Confirm DNS / domain checks have been completed out-of-band.`)) return;
    this.pendingId = s.id;
    this.senders.verify(s.id).subscribe({
      next: () => { this.pendingId = null; this.fetchPage(); },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Verify failed';
        this.pendingId = null;
      },
    });
  }

  revoke(s: EmailSender): void {
    if (!confirm(`Revoke ${s.address}? Future campaigns will not be able to send from this address.`)) return;
    this.pendingId = s.id;
    this.senders.revoke(s.id).subscribe({
      next: () => { this.pendingId = null; this.fetchPage(); },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Revoke failed';
        this.pendingId = null;
      },
    });
  }
}
