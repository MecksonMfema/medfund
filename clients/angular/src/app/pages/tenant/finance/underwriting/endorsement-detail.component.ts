import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import {
  EndorsementResponse,
  EndorsementService,
} from '../../../../core/services/endorsement.service';
import { NavigationService } from '../../../../core/services/navigation.service';
import { PermissionService } from '../../../../core/security/permission.service';
import { ToastService } from '../../../../shared/components/toast/toast.service';
import { extractErrorMessage } from '../../../../core/util/http-errors';

/**
 * Endorsement detail — full audit trail plus the same approve /
 * commit / void controls as the queue. Landing target for the queue's
 * "Detail" button. Mirrors the shape of the commission-adjustment
 * detail from Phase 11.
 */
@Component({
  selector: 'app-endorsement-detail',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './endorsement-detail.component.html',
  styleUrl: './endorsement-review-queue.component.scss',
})
export class EndorsementDetailComponent implements OnInit {
  loading = false;
  row: EndorsementResponse | null = null;
  actionInProgress = false;
  currentUserEmail = '';

  voidOpen = false;
  voidReason = '';
  voidSubmitting = false;

  constructor(
    private route: ActivatedRoute,
    private svc: EndorsementService,
    private navService: NavigationService,
    private permissionService: PermissionService,
    private toast: ToastService,
  ) {}

  ngOnInit(): void {
    this.currentUserEmail = this.navService.getUserInfo().email || '';
    this.route.paramMap.subscribe(p => {
      const id = p.get('id');
      if (id) this.load(id);
    });
  }

  canApprove(): boolean {
    return this.permissionService.has('policy:approve_endorsement');
  }

  isSameActor(): boolean {
    if (!this.row) return false;
    return !!this.currentUserEmail
      && this.currentUserEmail.toLowerCase() === this.row.draftActorEmail?.toLowerCase();
  }

  load(id: string): void {
    this.loading = true;
    this.svc.get(id).subscribe({
      next: row => { this.row = row; this.loading = false; },
      error: err => {
        this.toast.error(extractErrorMessage(err, 'Failed to load endorsement.'));
        this.row = null;
        this.loading = false;
      },
    });
  }

  approve(): void {
    if (!this.row || this.actionInProgress || this.isSameActor()) return;
    this.actionInProgress = true;
    this.svc.approve(this.row.id).subscribe({
      next: row => { this.row = row; this.actionInProgress = false; },
      error: err => {
        this.toast.error(extractErrorMessage(err, 'Approve failed.'));
        this.actionInProgress = false;
      },
    });
  }

  commit(): void {
    if (!this.row || this.actionInProgress) return;
    this.actionInProgress = true;
    this.svc.commit(this.row.id).subscribe({
      next: row => { this.row = row; this.actionInProgress = false; },
      error: err => {
        this.toast.error(extractErrorMessage(err, 'Commit failed.'));
        this.actionInProgress = false;
      },
    });
  }

  openVoid(): void {
    this.voidOpen = true;
    this.voidReason = '';
  }

  cancelVoid(): void {
    this.voidOpen = false;
    this.voidReason = '';
    this.voidSubmitting = false;
  }

  submitVoid(): void {
    if (!this.row) return;
    if (!this.voidReason.trim() || this.voidReason.trim().length < 5) {
      this.toast.warning('Reason must be at least 5 characters.');
      return;
    }
    this.voidSubmitting = true;
    this.svc.void(this.row.id, { reason: this.voidReason.trim() }).subscribe({
      next: row => {
        this.row = row;
        this.voidSubmitting = false;
        this.cancelVoid();
      },
      error: err => {
        this.toast.error(extractErrorMessage(err, 'Void failed.'));
        this.voidSubmitting = false;
      },
    });
  }
}
