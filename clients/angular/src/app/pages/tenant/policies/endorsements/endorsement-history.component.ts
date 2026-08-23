import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, RouterModule } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { PermissionService } from '../../../../core/security/permission.service';
import {
  CreateEndorsementPayload,
  EndorsementResponse,
  EndorsementService,
} from '../../../../core/services/endorsement.service';
import { EndorsementModalComponent } from './endorsement-modal.component';

/**
 * Per-policy endorsement history — the fallback page the plan calls
 * out when per-line detail pages don't yet host tabbed content. Route
 * lives at /tenant/policies/endorsements/:policySource/:policyId. Lists
 * every endorsement filed against the policy and lets a drafter open
 * the create modal (subject to {@code policy:draft_endorsement}). No
 * approve / commit / void here — those flows live on the review-queue
 * page where the four-eyes UI actually enforces the same-actor split.
 */
@Component({
  selector: 'app-endorsement-history',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterModule, IconComponent, EndorsementModalComponent],
  templateUrl: './endorsement-history.component.html',
  styleUrl: './endorsement-history.component.scss',
})
export class EndorsementHistoryComponent implements OnInit {
  loading = false;
  errorMessage: string | null = null;
  saving = false;

  policySource = '';
  policyId = '';
  /** Derived by inference from policySource — Life -> LIFE etc. */
  insuranceLine = '';
  policyLabel = '';

  rows: EndorsementResponse[] = [];

  modalOpen = false;

  constructor(
    private route: ActivatedRoute,
    private endorsementService: EndorsementService,
    private permissionService: PermissionService,
  ) {}

  ngOnInit(): void {
    this.route.paramMap.subscribe(params => {
      this.policySource = params.get('policySource') ?? '';
      this.policyId     = params.get('policyId') ?? '';
      this.insuranceLine = inferInsuranceLine(this.policySource);
      this.policyLabel   = `${prettySource(this.policySource)} ${this.policyId.substring(0, 8)}…`;
      this.load();
    });
  }

  canDraft(): boolean {
    return this.permissionService.has('policy:draft_endorsement');
  }

  load(): void {
    if (!this.policyId || !this.policySource) return;
    this.loading = true;
    this.errorMessage = null;
    this.endorsementService.listByPolicy(this.policyId, this.policySource).subscribe({
      next: rows => {
        this.rows = rows;
        this.loading = false;
      },
      error: err => {
        this.errorMessage = err?.error?.detail
          || err?.error?.title
          || 'Failed to load endorsements for this policy.';
        this.loading = false;
      },
    });
  }

  openModal(): void {
    if (!this.canDraft()) return;
    this.modalOpen = true;
  }

  closeModal(): void {
    this.modalOpen = false;
  }

  onSubmit(payload: CreateEndorsementPayload): void {
    if (this.saving) return;
    this.saving = true;
    this.endorsementService.create(payload).subscribe({
      next: row => {
        this.rows = [row, ...this.rows];
        this.saving = false;
        this.modalOpen = false;
      },
      error: err => {
        this.errorMessage = err?.error?.detail
          || err?.error?.title
          || 'Could not save the endorsement.';
        this.saving = false;
      },
    });
  }
}

function inferInsuranceLine(source: string): string {
  switch (source) {
    case 'LIFE_POLICY':       return 'LIFE';
    case 'FUNERAL_POLICY':    return 'FUNERAL';
    case 'DISABILITY_POLICY': return 'DISABILITY';
    case 'TRAVEL_POLICY':     return 'TRAVEL';
    case 'VEHICLE_POLICY':    return 'VEHICLE';
    case 'PROPERTY_POLICY':   return 'PROPERTY';
    default: return '';
  }
}

function prettySource(source: string): string {
  if (!source) return '';
  const first = source.split('_')[0];
  return first.charAt(0).toUpperCase() + first.slice(1).toLowerCase();
}
