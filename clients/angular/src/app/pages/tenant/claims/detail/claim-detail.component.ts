import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import {
  Claim,
  ClaimLine,
  ClaimsService,
} from '../../../../core/services/claims.service';
import {
  ClaimsConfigService,
  RejectionReason,
} from '../../../../core/services/claims-config.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { CurrencyFormatPipe } from '../../../../shared/pipes/currency-format.pipe';
import { HumanizePipe } from '../../../../shared/pipes/humanize.pipe';

type Tab = 'services' | 'header' | 'audit';

@Component({
  selector: 'app-claim-detail',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent, SelectComponent, SkeletonComponent, CurrencyFormatPipe, HumanizePipe],
  templateUrl: './claim-detail.component.html',
  styleUrl: './claim-detail.component.scss',
})
export class ClaimDetailComponent implements OnInit {
  claim: Claim | null = null;
  lines: ClaimLine[] = [];
  rejectionReasons: RejectionReason[] = [];
  loading = false;
  busy = false;
  errorMessage: string | null = null;
  successMessage: string | null = null;
  activeTab: Tab = 'services';
  rejectingCode = '';

  constructor(
    private claims: ClaimsService,
    private config: ClaimsConfigService,
    private route: ActivatedRoute,
    private router: Router,
  ) {}

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (!id) { this.router.navigate(['/tenant/claims']); return; }
    this.loading = true;
    forkJoin({
      claim: this.claims.getById(id),
      lines: this.claims.getLines(id),
      reasons: this.config.listRejectionReasons(true),
    }).subscribe({
      next: ({ claim, lines, reasons }) => {
        this.claim = claim;
        this.lines = lines;
        this.rejectionReasons = reasons;
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load claim';
        this.loading = false;
      },
    });
  }

  setTab(t: Tab): void { this.activeTab = t; }

  get rejectionReasonOptions(): SelectOption[] {
    return this.rejectionReasons.map(r => ({
      value: r.code,
      label: r.code,
      description: r.description,
    }));
  }

  // ── Lifecycle actions ────────────────────────────────────────────────

  adjudicate(): void {
    if (!this.claim) return;
    this.busy = true;
    this.errorMessage = null;
    this.claims.adjudicate(this.claim.id).subscribe({
      next: (updated) => {
        this.claim = updated;
        this.busy = false;
        this.successMessage = `Pipeline ran. New status: ${updated.status}.`;
        // Refresh lines (line approved amounts may change).
        this.claims.getLines(updated.id).subscribe({
          next: (lines) => { this.lines = lines; },
        });
      },
      error: (err) => {
        this.busy = false;
        this.errorMessage = err?.error?.detail || 'Adjudication failed';
      },
    });
  }

  approve(): void {
    if (!this.claim) return;
    if (!confirm(`Mark claim ${this.claim.claimNumber} as ADJUDICATED (approved at ${this.claim.claimedAmount})?`)) return;
    this.transition('ADJUDICATED', 'Manually marked adjudicated');
  }

  reject(): void {
    if (!this.claim) return;
    if (!this.rejectingCode) {
      this.errorMessage = 'Pick a rejection reason first';
      return;
    }
    if (!confirm(`Reject claim ${this.claim.claimNumber} with reason ${this.rejectingCode}?`)) return;
    this.transition('REJECTED', this.rejectingCode);
  }

  cancel(): void {
    if (!this.claim) return;
    if (!confirm(`Cancel claim ${this.claim.claimNumber}? This is irreversible.`)) return;
    this.transition('CANCELLED', 'Cancelled by operator');
  }

  private transition(status: any, _reason: string): void {
    if (!this.claim) return;
    this.busy = true;
    this.errorMessage = null;
    this.claims.updateStatus(this.claim.id, status).subscribe({
      next: (updated) => {
        this.claim = updated;
        this.busy = false;
        this.successMessage = `Status updated to ${updated.status}.`;
      },
      error: (err) => {
        this.busy = false;
        this.errorMessage = err?.error?.detail || 'Status update failed';
      },
    });
  }

  // Determines whether the current claim status admits the given action.
  // Keeps the action bar honest — terminal statuses don't get re-decided.
  canAct(action: 'adjudicate' | 'approve' | 'reject' | 'cancel'): boolean {
    if (!this.claim) return false;
    const s = this.claim.status;
    switch (action) {
      case 'adjudicate': return s === 'VERIFIED';
      case 'approve':    return s === 'IN_ADJUDICATION' || s === 'PENDING_INFO';
      case 'reject':     return s === 'IN_ADJUDICATION' || s === 'PENDING_INFO' || s === 'VERIFIED';
      case 'cancel':     return s !== 'PAID' && s !== 'CANCELLED';
      default:           return false;
    }
  }
}
