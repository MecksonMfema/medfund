import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import {
  Assignment,
  Producer,
  ProducerService,
} from '../../../core/services/producer.service';
import { IconComponent } from '../../../shared/components/icon/icon.component';
import {
  DataTableComponent,
  TableColumn,
} from '../../../shared/components/data-table/data-table.component';
import {
  TerminateProducerModalComponent,
  TerminateProducerPayload,
} from './terminate-producer-modal.component';
import { PermissionService } from '../../../core/security/permission.service';

@Component({
  selector: 'app-producer-assignments',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent,
            DataTableComponent, TerminateProducerModalComponent],
  templateUrl: './producer-assignments.component.html',
  styleUrl: './producers-list.component.scss',
})
export class ProducerAssignmentsComponent implements OnInit {
  producer: Producer | null = null;
  rows: Assignment[] = [];
  loading = false;
  errorMessage: string | null = null;
  successMessage: string | null = null;
  openCount: number | null = null;

  showTerminate = false;
  terminating = false;
  canTerminate = false;

  page = 1;
  pageSize = 50;

  readonly columns: TableColumn[] = [
    { key: 'memberId',      label: 'Member' },
    { key: 'effectiveFrom', label: 'Effective from' },
    { key: 'changeReason',  label: 'Reason' },
    { key: 'createdAt',     label: 'Created' },
  ];

  constructor(private route: ActivatedRoute,
              private router: Router,
              private svc: ProducerService,
              private perms: PermissionService) {}

  ngOnInit(): void {
    const producerId = this.route.snapshot.paramMap.get('id');
    if (!producerId) { this.errorMessage = 'Missing producer id'; return; }
    this.perms.permissions$.subscribe(() => {
      this.canTerminate = this.perms.has('finance.producer:terminate');
    });
    this.loadProducer(producerId);
    this.fetch(producerId);
  }

  openTerminate(): void {
    this.showTerminate = true;
    this.successMessage = null;
    this.errorMessage = null;
  }

  cancelTerminate(): void { this.showTerminate = false; }

  onTerminateSubmit(payload: TerminateProducerPayload): void {
    if (!this.producer) return;
    const producerId = this.producer.id;
    this.terminating = true;
    this.svc.terminateProducer(producerId, payload).subscribe({
      next: p => {
        this.terminating = false;
        this.showTerminate = false;
        this.producer = p;
        this.successMessage = 'Producer terminated. Redirecting to bulk reassign…';
        this.router.navigate(['/tenant/admin/producers', producerId, 'reassign']);
      },
      error: err => {
        this.terminating = false;
        this.errorMessage =
          err?.error?.detail || err?.error?.title || 'Termination failed';
      },
    });
  }

  private loadProducer(id: string): void {
    this.svc.getProducer(id).subscribe({
      next: p => { this.producer = p; },
      error: err => {
        this.errorMessage = err?.error?.detail || 'Failed to load producer';
      },
    });
    this.svc.countOpenAssignments(id).subscribe({
      next: c => { this.openCount = c; },
    });
  }

  private fetch(producerId: string): void {
    this.loading = true;
    this.svc.listProducerAssignments(producerId, this.page - 1, this.pageSize).subscribe({
      next: rows => { this.rows = rows; this.loading = false; },
      error: err => {
        this.errorMessage = err?.error?.detail || 'Failed to load assignments';
        this.rows = [];
        this.loading = false;
      },
    });
  }

  onPageChange(page: number): void {
    this.page = page;
    if (this.producer) this.fetch(this.producer.id);
  }
}
