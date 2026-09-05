import { CommonModule } from '@angular/common';
import { Component, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { HasPermissionDirective } from '../../../../shared/directives/has-permission.directive';
import { ActivityFeedComponent, ActivityItem }
  from '../../../../shared/components/activity-feed/activity-feed.component';
import { ConfirmService } from '../../../../shared/components/confirm-dialog/confirm.service';
import { ToastService } from '../../../../shared/components/toast/toast.service';
import { SiuCaseDetail, SiuEvidence, SiuReferral, SiuService } from './siu.service';

/**
 * SIU case detail page. §B Phase 10 widens the MVP 3-tab shape to 5 tabs
 * (Overview, Flags, Evidence, Referrals, Activity), inlines evidence +
 * referral capture forms, and folds notes + evidence + referrals into a
 * single sorted activity timeline via {@code <app-activity-feed>}.
 * All action buttons are role-gated via {@code *hasPermission} on the
 * eight {@code claims:siu:*} keys defined in Phase 7.
 */
@Component({
  selector: 'app-siu-case-detail',
  standalone: true,
  imports: [
    CommonModule, FormsModule, RouterLink,
    IconComponent, HasPermissionDirective, ActivityFeedComponent,
  ],
  templateUrl: './siu-case-detail.component.html',
})
export class SiuCaseDetailComponent implements OnInit {
  case: SiuCaseDetail | null = null;
  loading = false;
  busy = false;
  errorMessage: string | null = null;

  activeTab: 'overview' | 'flags' | 'evidence' | 'referrals' | 'activity' = 'overview';

  // §B Phase 8 — investigator "propose closure" form.
  showProposeForm = false;
  proposeOutcome: 'CONFIRMED_FRAUD' | 'REFERRED_LAW_ENFORCEMENT' | 'ACTION_TAKEN'
    = 'CONFIRMED_FRAUD';
  savedAmount = '';
  savedCurrency = 'USD';
  closureReason = '';

  // §B Phase 10 — evidence capture form. file-service byte-upload endpoint
  // is not yet exposed via gateway (only /invoice-pdf/render exists today),
  // so operators paste an S3 / Drive / URL handle for now — matches the
  // deferred-upload pattern already in place in SubmitClaimComponent.
  showEvidenceForm = false;
  evidenceRef = '';
  evidenceDescription = '';
  evidenceType: SiuEvidence['evidenceType'] = 'DOCUMENT';

  // §B Phase 10 — referral capture form.
  showReferralForm = false;
  referralTarget: SiuReferral['referralTo'] = 'LAW_ENFORCEMENT';
  referralReference = '';

  constructor(
    private siu: SiuService,
    private route: ActivatedRoute,
    private router: Router,
    private confirm: ConfirmService,
    private toast: ToastService,
  ) {}

  ngOnInit(): void {
    const caseId = this.route.snapshot.paramMap.get('caseId');
    if (!caseId) {
      this.errorMessage = 'Missing case id in URL';
      return;
    }
    this.fetch(caseId);
  }

  fetch(caseId: string): void {
    this.loading = true;
    this.siu.get(caseId).subscribe({
      next: (c) => {
        this.case = c;
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || err?.error?.title
          || 'Failed to load case';
        this.loading = false;
      },
    });
  }

  refresh(): void {
    if (this.case) this.fetch(this.case.id);
  }

  async startReview(): Promise<void> {
    if (!this.case) return;
    const ok = await this.confirm.ask({
      title: 'Start review',
      message: `Move case ${this.case.caseNumber} from OPEN to UNDER_REVIEW?`,
      confirmLabel: 'Start review',
    });
    if (!ok) return;
    this.busy = true;
    this.siu.startReview(this.case.id).subscribe({
      next: (updated) => {
        this.case = updated;
        this.busy = false;
        this.toast.success('Case moved to UNDER_REVIEW');
      },
      error: (err) => {
        this.busy = false;
        this.toast.error(err?.error?.detail || err?.error?.title
          || 'Failed to start review');
      },
    });
  }

  openProposeForm(): void {
    this.showProposeForm = true;
    this.proposeOutcome = 'CONFIRMED_FRAUD';
    this.savedAmount = '';
    this.savedCurrency = 'USD';
    this.closureReason = '';
  }

  cancelProposeForm(): void {
    this.showProposeForm = false;
  }

  submitPropose(): void {
    if (!this.case) return;
    if (!this.savedAmount || !this.savedCurrency || !this.closureReason.trim()) {
      this.toast.warning('savedAmount, savedCurrency and closureReason are all required.');
      return;
    }
    this.busy = true;
    this.siu.proposeClosure(this.case.id, {
      outcome: this.proposeOutcome,
      savedAmount: this.savedAmount,
      savedCurrency: this.savedCurrency,
      closureReason: this.closureReason.trim(),
    }).subscribe({
      next: (updated) => {
        this.case = updated;
        this.busy = false;
        this.showProposeForm = false;
        this.toast.success('Closure proposed — awaiting supervisor approval');
      },
      error: (err) => {
        this.busy = false;
        this.toast.error(err?.error?.detail || err?.error?.title
          || 'Failed to propose closure');
      },
    });
  }

  async approveClosure(): Promise<void> {
    if (!this.case) return;
    const ok = await this.confirm.ask({
      title: 'Approve pending closure',
      message: `Approve the proposed ${this.case.proposedOutcome || ''} closure? `
        + `You must be a different actor from the proposer (${this.case.proposedByEmail || 'unknown'}).`,
      confirmLabel: 'Approve',
    });
    if (!ok) return;
    this.busy = true;
    this.siu.approveClosure(this.case.id).subscribe({
      next: (updated) => {
        this.case = updated;
        this.busy = false;
        this.toast.success('Closure approved — case closed');
      },
      error: (err) => {
        this.busy = false;
        this.toast.error(err?.error?.detail || err?.error?.title
          || 'Failed to approve closure');
      },
    });
  }

  async rejectClosure(): Promise<void> {
    if (!this.case) return;
    const note = window.prompt('Rejection note back to the investigator?', '');
    if (note === null || note.trim().length === 0) return;
    this.busy = true;
    this.siu.rejectClosure(this.case.id, note.trim()).subscribe({
      next: (updated) => {
        this.case = updated;
        this.busy = false;
        this.toast.success('Closure rejected — sent back to UNDER_REVIEW');
      },
      error: (err) => {
        this.busy = false;
        this.toast.error(err?.error?.detail || err?.error?.title
          || 'Failed to reject closure');
      },
    });
  }

  async reopen(): Promise<void> {
    if (!this.case) return;
    const reason = window.prompt('Reason for reopening this case?', '');
    if (reason === null || reason.trim().length === 0) return;
    this.busy = true;
    this.siu.reopen(this.case.id, reason.trim()).subscribe({
      next: (updated) => {
        this.case = updated;
        this.busy = false;
        this.toast.success('Case reopened — back to UNDER_REVIEW');
      },
      error: (err) => {
        this.busy = false;
        this.toast.error(err?.error?.detail || err?.error?.title
          || 'Failed to reopen case');
      },
    });
  }

  async closeDismissed(): Promise<void> {
    if (!this.case) return;
    const reason = window.prompt('Reason for dismissing?', '');
    if (reason === null || reason.trim().length === 0) return;
    const ok = await this.confirm.ask({
      title: 'Close as DISMISSED',
      message: `Close case ${this.case.caseNumber} as false-positive dismissed?`,
      confirmLabel: 'Close',
      danger: true,
    });
    if (!ok) return;
    this.busy = true;
    this.siu.closeDismissed(this.case.id, {
      savedAmount: null, savedCurrency: null, closureReason: reason.trim(),
    }).subscribe({
      next: (updated) => {
        this.case = updated;
        this.busy = false;
        this.toast.success('Case closed — DISMISSED_FALSE_POSITIVE');
      },
      error: (err) => {
        this.busy = false;
        this.toast.error(err?.error?.detail || err?.error?.title
          || 'Failed to close case');
      },
    });
  }

  // ── §B Phase 10 — evidence capture ──────────────────────────────────

  openEvidenceForm(): void {
    this.showEvidenceForm = true;
    this.evidenceRef = '';
    this.evidenceDescription = '';
    this.evidenceType = 'DOCUMENT';
  }

  cancelEvidenceForm(): void {
    this.showEvidenceForm = false;
  }

  submitEvidence(): void {
    if (!this.case) return;
    if (!this.evidenceRef.trim() || !this.evidenceDescription.trim()) {
      this.toast.warning('Storage handle (URL / S3 key) and description are both required.');
      return;
    }
    this.busy = true;
    this.siu.addEvidence(this.case.id, {
      fileServiceRef: this.evidenceRef.trim(),
      description: this.evidenceDescription.trim(),
      evidenceType: this.evidenceType,
    }).subscribe({
      next: () => {
        this.busy = false;
        this.showEvidenceForm = false;
        this.toast.success('Evidence attached');
        // Round-trip a full case fetch — cheaper than splicing since the
        // activity timeline needs the new EVIDENCE_ADDED note too.
        this.refresh();
      },
      error: (err) => {
        this.busy = false;
        this.toast.error(err?.error?.detail || err?.error?.title
          || 'Failed to attach evidence');
      },
    });
  }

  // ── §B Phase 10 — external referral capture ─────────────────────────

  openReferralForm(): void {
    this.showReferralForm = true;
    this.referralTarget = 'LAW_ENFORCEMENT';
    this.referralReference = '';
  }

  cancelReferralForm(): void {
    this.showReferralForm = false;
  }

  submitReferral(): void {
    if (!this.case) return;
    this.busy = true;
    this.siu.addReferral(this.case.id, {
      referralTo: this.referralTarget,
      referralReference: this.referralReference.trim() || null,
    }).subscribe({
      next: () => {
        this.busy = false;
        this.showReferralForm = false;
        this.toast.success('Referral recorded');
        this.refresh();
      },
      error: (err) => {
        this.busy = false;
        this.toast.error(err?.error?.detail || err?.error?.title
          || 'Failed to record referral');
      },
    });
  }

  // ── Activity timeline — merges notes + evidence + referrals ─────────

  /** Merged, newest-first activity feed for the {@code <app-activity-feed>}
   *  component. Notes carry their real note_type; evidence/referrals are
   *  synthesised entries so investigators see one chronological trail. */
  get activityItems(): ActivityItem[] {
    if (!this.case) return [];
    const items: ActivityItem[] = [];

    for (const n of this.case.notes) {
      items.push({
        icon: iconForNoteType(n.noteType),
        iconColor: colorForNoteType(n.noteType),
        title: labelForNoteType(n.noteType),
        description: n.body,
        timestamp: new Date(n.createdAt),
        actor: n.authorEmail,
      });
    }
    for (const e of this.case.evidence) {
      items.push({
        icon: 'file',
        iconColor: '#6366f1',
        title: `Evidence · ${e.evidenceType}`,
        description: `${e.description} (${e.fileServiceRef})`,
        timestamp: new Date(e.uploadedAt),
        actor: e.uploadedByEmail,
      });
    }
    for (const r of this.case.referrals) {
      items.push({
        icon: 'send',
        iconColor: '#f59e0b',
        title: `Referred to ${r.referralTo}`,
        description: r.referralReference
          ? `External reference: ${r.referralReference}`
          : 'No external reference yet',
        timestamp: new Date(r.referredAt),
        actor: r.referredByEmail,
      });
    }
    return items.sort((a, b) => b.timestamp.getTime() - a.timestamp.getTime());
  }

  // ── State-gated getters — used to enable/disable transition buttons ─

  get canStartReview(): boolean {
    return !!this.case && this.case.status === 'OPEN' && !this.busy;
  }

  get canClose(): boolean {
    return !!this.case && this.case.status === 'UNDER_REVIEW' && !this.busy;
  }

  get canApproveOrReject(): boolean {
    return !!this.case && this.case.status === 'PENDING_APPROVAL' && !this.busy;
  }

  get canReopen(): boolean {
    return !!this.case && !!this.case.status && this.case.status.startsWith('CLOSED_') && !this.busy;
  }

  get canAddEvidence(): boolean {
    // Any pre-closure state is a valid evidence-capture window; closed cases
    // must be reopened first (mirrors backend: addEvidence requires the case
    // to exist and doesn't restrict by status).
    return !!this.case && !this.busy;
  }

  get canAddReferral(): boolean {
    return !!this.case && !this.busy;
  }
}

// ── Icon + color mappings for the activity feed ─────────────────────────

function iconForNoteType(type: string): string {
  switch (type) {
    case 'STATUS_CHANGE':  return 'refresh-cw';
    case 'ASSIGNED':       return 'user-plus';
    case 'EVIDENCE_ADDED': return 'file';
    case 'REFERRAL_ADDED': return 'send';
    case 'FLAG_LINKED':    return 'link';
    default:               return 'message-square';
  }
}

function colorForNoteType(type: string): string {
  switch (type) {
    case 'STATUS_CHANGE':  return '#3b82f6';
    case 'ASSIGNED':       return '#10b981';
    case 'EVIDENCE_ADDED': return '#6366f1';
    case 'REFERRAL_ADDED': return '#f59e0b';
    case 'FLAG_LINKED':    return '#ef4444';
    default:               return '#64748b';
  }
}

function labelForNoteType(type: string): string {
  switch (type) {
    case 'STATUS_CHANGE':  return 'Status change';
    case 'ASSIGNED':       return 'Assigned';
    case 'EVIDENCE_ADDED': return 'Evidence added';
    case 'REFERRAL_ADDED': return 'Referral added';
    case 'FLAG_LINKED':    return 'Flag linked';
    default:               return 'Comment';
  }
}
