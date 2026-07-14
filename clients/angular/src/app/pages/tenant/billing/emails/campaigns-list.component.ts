import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import {
  EmailCampaignRow,
  EmailCampaignsPageResponse,
  EmailCampaignsService,
} from '../../../../core/services/email-campaigns.service';
import {
  DataTableComponent,
  TableAction,
  TableColumn,
} from '../../../../shared/components/data-table/data-table.component';

@Component({
  selector: 'app-campaigns-list',
  standalone: true,
  imports: [CommonModule, RouterLink, DataTableComponent],
  templateUrl: './campaigns-list.component.html',
  styleUrl: './campaigns-list.component.scss',
})
export class CampaignsListComponent implements OnInit {
  rows: EmailCampaignRow[] = [];
  loading = false;
  errorMessage: string | null = null;
  pendingId: string | null = null;

  page = 1;
  pageSize = 50;
  totalCount = 0;
  totalPages = 1;
  sortKey = 'createdAt';
  sortDirection: 'asc' | 'desc' = 'desc';
  searchTerm = '';

  readonly columns: TableColumn[] = [
    { key: 'subject',           label: 'Subject',    sortable: true },
    { key: 'senderAddress',     label: 'Sender',     sortable: true },
    { key: 'status',            label: 'Status',     sortable: true },
    { key: 'recipientCount',    label: 'Recipients', sortable: true },
    { key: 'sentAt',            label: 'Sent',       sortable: true, type: 'date' },
    { key: 'createdAt',         label: 'Created',    sortable: true, type: 'date' },
  ];

  readonly actions: TableAction[] = [
    {
      label: 'Edit',
      icon: 'edit',
      color: 'default',
      handler: (row: EmailCampaignRow) => this.edit(row),
      visible: (row: EmailCampaignRow) => row.status === 'draft',
    },
    {
      label: 'View',
      icon: 'eye',
      color: 'default',
      handler: (row: EmailCampaignRow) => this.edit(row),
      visible: (row: EmailCampaignRow) => row.status !== 'draft',
    },
    {
      label: 'Send',
      icon: 'send',
      color: 'primary',
      handler: (row: EmailCampaignRow) => this.send(row),
      visible: (row: EmailCampaignRow) => row.status === 'draft',
    },
    {
      label: 'Delete',
      icon: 'trash',
      color: 'danger',
      handler: (row: EmailCampaignRow) => this.remove(row),
      visible: (row: EmailCampaignRow) => row.status === 'draft',
    },
  ];

  constructor(private campaigns: EmailCampaignsService, private router: Router) {}

  ngOnInit(): void { this.fetchPage(); }

  fetchPage(): void {
    this.loading = true;
    this.campaigns.listPaged({
      q: this.searchTerm || undefined,
      sortKey: this.sortKey,
      sortDirection: this.sortDirection,
      page: this.page - 1,
      size: this.pageSize,
    }).subscribe({
      next: (resp: EmailCampaignsPageResponse<EmailCampaignRow>) => {
        this.rows = resp.content ?? [];
        this.totalCount = resp.total;
        this.totalPages = resp.totalPages;
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load campaigns';
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

  edit(c: EmailCampaignRow): void {
    this.router.navigate(['/tenant/billing/emails/send'], { queryParams: { id: c.id } });
  }

  send(c: EmailCampaignRow): void {
    if (!confirm(`Send "${c.subject}" to all members matching the audience filter? This will compute the recipient count and stamp sent_at.`)) return;
    this.pendingId = c.id;
    this.campaigns.send(c.id).subscribe({
      next: () => { this.pendingId = null; this.fetchPage(); },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Send failed';
        this.pendingId = null;
      },
    });
  }

  remove(c: EmailCampaignRow): void {
    if (!confirm(`Delete campaign "${c.subject}"?`)) return;
    this.pendingId = c.id;
    this.campaigns.delete(c.id).subscribe({
      next: () => { this.pendingId = null; this.fetchPage(); },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Delete failed';
        this.pendingId = null;
      },
    });
  }
}
