import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { forkJoin, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import {
  CessionRuleLink,
  CreateTreatyPayload,
  InsuranceLine,
  Reinsurer,
  ReinsuranceService,
  Treaty,
  TreatyApplicableLine,
  TreatyLayer,
  TreatyParticipant,
  TreatyType,
  UpsertTreatyLayerPayload,
  UpsertTreatyParticipantPayload,
} from '../../../core/services/reinsurance.service';
import { Producer, ProducerService } from '../../../core/services/producer.service';
import { IconComponent } from '../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../shared/components/select/select.component';

const INSURANCE_LINES: InsuranceLine[] = [
  'HEALTH', 'LIFE', 'FUNERAL', 'GROUP', 'TRAVEL', 'DISABILITY', 'VEHICLE', 'PROPERTY',
];

const TREATY_TYPES: TreatyType[] = ['QUOTA_SHARE', 'SURPLUS_SHARE', 'EXCESS_OF_LOSS', 'STOP_LOSS'];

/**
 * Treaty create + edit. Layout mirrors /tenant/billing/transactions/add:
 * centered page container, page-header banner, form-card with section
 * subgrid, .field / .field-control inputs, .form-actions footer.
 *
 * The /new route shows only the Details section (draft creation). The
 * /:id route shows every section (participants, lines, layers, cession
 * rules) plus the activation footer.
 */
@Component({
  selector: 'app-treaty-edit',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent, SelectComponent],
  templateUrl: './treaty-edit.component.html',
  styleUrl: './treaty-edit.component.scss',
})
export class TreatyEditComponent implements OnInit {
  readonly INSURANCE_LINES = INSURANCE_LINES;
  readonly TREATY_TYPES = TREATY_TYPES;
  readonly Math = Math;

  readonly treatyTypeOptions: SelectOption[] = [
    { value: 'QUOTA_SHARE',    label: 'Quota Share' },
    { value: 'SURPLUS_SHARE',  label: 'Surplus Share' },
    { value: 'EXCESS_OF_LOSS', label: 'Excess of Loss' },
    { value: 'STOP_LOSS',      label: 'Stop Loss' },
  ];

  readonly shareRoleOptions: SelectOption[] = [
    { value: 'LEADER',    label: 'Leader' },
    { value: 'FOLLOWING', label: 'Following' },
  ];

  readonly insuranceLineOptions: SelectOption[] = INSURANCE_LINES.map(l => ({ value: l, label: l }));

  treatyId: string | null = null;
  treaty: Treaty | null = null;
  loading = false;
  saving = false;
  errorMessage: string | null = null;
  successMessage: string | null = null;

  header: CreateTreatyPayload = this.emptyHeader();

  layers: TreatyLayer[] = [];
  newLayer: UpsertTreatyLayerPayload = this.emptyLayer();

  participants: TreatyParticipant[] = [];
  newParticipant: UpsertTreatyParticipantPayload = this.emptyParticipant();

  applicableLines: TreatyApplicableLine[] = [];
  newLine: InsuranceLine = 'HEALTH';

  cessionRules: CessionRuleLink[] = [];
  newRuleId = '';

  // Reinsurer search-select (debounced typeahead per feedback_no_raw_id_inputs).
  reinsurerSearchTerm = '';
  reinsurerMatches: Reinsurer[] = [];
  private reinsurerSearchTimer: ReturnType<typeof setTimeout> | null = null;

  // Producer search-select — payload sends producer_id; producer_ref snapshots
  // the display label so the free-text audit trail survives. Matches
  // feedback_no_raw_id_inputs (no bare UUID typing).
  producerSearchTerm = '';
  producerMatches: Producer[] = [];
  producerSearching = false;
  private producerSearchTimer: ReturnType<typeof setTimeout> | null = null;

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private svc: ReinsuranceService,
    private producerSvc: ProducerService,
  ) {}

  get isNew(): boolean { return this.treatyId === null; }
  get isDraft(): boolean { return this.treaty?.status === 'DRAFT' || this.isNew; }
  get isNonProportional(): boolean {
    const type = this.treaty?.treatyType ?? this.header.treatyType;
    return type === 'EXCESS_OF_LOSS' || type === 'STOP_LOSS';
  }

  get participantShareSum(): number {
    return this.participants.reduce((sum, p) => sum + Number(p.sharePct), 0);
  }

  get shareSumOk(): boolean {
    return Math.abs(this.participantShareSum - 100) < 0.0001;
  }

  get activationBlockers(): string[] {
    const blockers: string[] = [];
    if (!this.shareSumOk) {
      blockers.push(`Participants must sum to 100% (currently ${this.participantShareSum.toFixed(4)}%)`);
    }
    if (!this.applicableLines.length) blockers.push('At least one applicable insurance line');
    if (this.isNonProportional && !this.layers.length) blockers.push('XoL/StopLoss requires at least one layer');
    return blockers;
  }

  ngOnInit(): void {
    this.treatyId = this.route.snapshot.paramMap.get('id');
    if (this.treatyId) this.loadTreaty(this.treatyId);
  }

  private loadTreaty(id: string): void {
    this.loading = true;
    forkJoin({
      treaty:          this.svc.getTreaty(id),
      layers:          this.svc.listLayers(id).pipe(catchError(() => of([] as TreatyLayer[]))),
      participants:    this.svc.listParticipants(id).pipe(catchError(() => of([] as TreatyParticipant[]))),
      applicableLines: this.svc.listApplicableLines(id).pipe(catchError(() => of([] as TreatyApplicableLine[]))),
      cessionRules:    this.svc.listCessionRules(id).pipe(catchError(() => of([] as CessionRuleLink[]))),
    }).subscribe({
      next: (result) => {
        this.treaty = result.treaty;
        this.header = {
          treatyRef: result.treaty.treatyRef,
          treatyType: result.treaty.treatyType,
          declaredCurrency: result.treaty.declaredCurrency,
          inceptionDate: result.treaty.inceptionDate,
          expiryDate: result.treaty.expiryDate,
          aggregateLimit: result.treaty.aggregateLimit,
          aggregateLimitCurrency: result.treaty.aggregateLimitCurrency,
          expectedAnnualPremium: result.treaty.expectedAnnualPremium,
          producerId: result.treaty.producerId ?? null,
          producerRef: result.treaty.producerRef,
        };
        this.producerSearchTerm = result.treaty.producerRef ?? '';
        // If the treaty carries a producerId, resolve its current name so
        // the selected-pill shows the friendly label rather than the UUID.
        if (result.treaty.producerId) {
          this.producerSvc.getProducer(result.treaty.producerId).subscribe({
            next: p => { this.producerSearchTerm = `${p.producerCode} - ${p.name}`; },
          });
        }
        this.layers = result.layers;
        this.participants = result.participants;
        this.applicableLines = result.applicableLines;
        this.cessionRules = result.cessionRules;
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load treaty';
        this.loading = false;
      },
    });
  }

  saveHeader(): void {
    if (!this.header.treatyRef?.trim()) {
      this.errorMessage = 'Treaty reference is required';
      return;
    }
    if (this.header.inceptionDate >= this.header.expiryDate) {
      this.errorMessage = 'Expiry date must be after inception';
      return;
    }
    this.saving = true;
    this.clearMessages();
    const stream = this.treatyId
      ? this.svc.updateTreaty(this.treatyId, this.header)
      : this.svc.createTreaty(this.header);
    stream.subscribe({
      next: (saved) => {
        this.saving = false;
        this.successMessage = this.isNew ? 'Treaty created' : 'Treaty updated';
        if (this.isNew) {
          this.router.navigate(['/tenant/admin/reinsurance/treaties', saved.id]);
        } else {
          this.treaty = saved;
        }
      },
      error: (err) => {
        this.saving = false;
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Save failed';
      },
    });
  }

  activate(): void {
    if (!this.treatyId) return;
    if (this.activationBlockers.length) return;
    if (!confirm('Activate this treaty? DRAFT edits will no longer be possible.')) return;
    this.saving = true;
    this.svc.activateTreaty(this.treatyId).subscribe({
      next: (saved) => {
        this.saving = false;
        this.successMessage = 'Treaty activated';
        this.treaty = saved;
      },
      error: (err) => {
        this.saving = false;
        this.errorMessage = err?.error?.detail || 'Activation failed';
      },
    });
  }

  voidDraft(): void {
    if (!this.treatyId) return;
    if (!confirm('Void this DRAFT treaty? It will transition to LAPSED and cannot be edited further.')) return;
    this.saving = true;
    this.svc.voidTreaty(this.treatyId).subscribe({
      next: (saved) => { this.saving = false; this.treaty = saved; this.successMessage = 'Treaty voided'; },
      error: (err) => {
        this.saving = false;
        this.errorMessage = err?.error?.detail || 'Void failed';
      },
    });
  }

  addLayer(): void {
    if (!this.treatyId) return;
    if (this.newLayer.layerLimit <= 0 || this.newLayer.rate < 0) {
      this.errorMessage = 'Layer limit must be positive and rate non-negative';
      return;
    }
    this.svc.createLayer(this.treatyId, this.newLayer).subscribe({
      next: (l) => { this.layers = [...this.layers, l].sort((a, b) => a.layerOrder - b.layerOrder); this.newLayer = this.emptyLayer(); },
      error: (err) => { this.errorMessage = err?.error?.detail || 'Layer add failed'; },
    });
  }

  removeLayer(l: TreatyLayer): void {
    if (!this.treatyId) return;
    if (!confirm(`Remove layer ${l.layerOrder}?`)) return;
    this.svc.deleteLayer(this.treatyId, l.id).subscribe({
      next: () => { this.layers = this.layers.filter(x => x.id !== l.id); },
      error: (err) => { this.errorMessage = err?.error?.detail || 'Layer remove failed'; },
    });
  }

  onReinsurerSearch(term: string): void {
    this.reinsurerSearchTerm = term;
    if (this.reinsurerSearchTimer) clearTimeout(this.reinsurerSearchTimer);
    if (!term || term.trim().length < 2) { this.reinsurerMatches = []; return; }
    this.reinsurerSearchTimer = setTimeout(() => {
      this.svc.listReinsurers(0, 200, true).subscribe({
        next: (page) => {
          const q = term.toLowerCase();
          this.reinsurerMatches = page.content.filter(r => r.name.toLowerCase().includes(q)).slice(0, 20);
        },
        error: () => { this.reinsurerMatches = []; },
      });
    }, 250);
  }

  pickReinsurer(r: Reinsurer): void {
    this.newParticipant.reinsurerId = r.id;
    this.reinsurerSearchTerm = r.name;
    this.reinsurerMatches = [];
  }

  clearReinsurer(): void {
    this.newParticipant.reinsurerId = '';
    this.reinsurerSearchTerm = '';
    this.reinsurerMatches = [];
  }

  onProducerSearch(term: string): void {
    this.producerSearchTerm = term;
    // Typing invalidates any previously-picked id — the payload should
    // only carry the id when the operator has confirmed a pick.
    this.header.producerId = null;
    this.header.producerRef = term.trim() || null;
    if (this.producerSearchTimer) clearTimeout(this.producerSearchTimer);
    if (!term || term.trim().length < 2) { this.producerMatches = []; return; }
    this.producerSearching = true;
    this.producerSearchTimer = setTimeout(() => {
      this.producerSvc.searchProducers(term.trim(), 10).subscribe({
        next: rows => { this.producerMatches = rows; this.producerSearching = false; },
        error: () => { this.producerMatches = []; this.producerSearching = false; },
      });
    }, 250);
  }

  pickProducer(p: Producer): void {
    this.header.producerId = p.id;
    // Snapshot the friendly label into producer_ref so the audit trail
    // still records the human-facing identifier at pick time.
    this.header.producerRef = `${p.producerCode} - ${p.name}`;
    this.producerSearchTerm = this.header.producerRef;
    this.producerMatches = [];
  }

  clearProducer(): void {
    this.header.producerId = null;
    this.header.producerRef = null;
    this.producerSearchTerm = '';
    this.producerMatches = [];
  }

  addParticipant(): void {
    if (!this.treatyId) return;
    if (!this.newParticipant.reinsurerId) {
      this.errorMessage = 'Select a reinsurer';
      return;
    }
    if (this.newParticipant.sharePct <= 0 || this.newParticipant.sharePct > 100) {
      this.errorMessage = 'Share must be between 0 and 100';
      return;
    }
    this.svc.upsertParticipant(this.treatyId, this.newParticipant).subscribe({
      next: (p) => {
        const idx = this.participants.findIndex(x => x.reinsurerId === p.reinsurerId);
        if (idx >= 0) this.participants = this.participants.map((x, i) => i === idx ? p : x);
        else this.participants = [...this.participants, p];
        this.newParticipant = this.emptyParticipant();
        this.reinsurerSearchTerm = '';
      },
      error: (err) => { this.errorMessage = err?.error?.detail || 'Participant save failed'; },
    });
  }

  removeParticipant(p: TreatyParticipant): void {
    if (!this.treatyId) return;
    if (!confirm(`Remove ${p.reinsurerName}?`)) return;
    this.svc.deleteParticipant(this.treatyId, p.reinsurerId).subscribe({
      next: () => { this.participants = this.participants.filter(x => x.reinsurerId !== p.reinsurerId); },
      error: (err) => { this.errorMessage = err?.error?.detail || 'Participant remove failed'; },
    });
  }

  addLine(): void {
    if (!this.treatyId) return;
    if (this.applicableLines.some(l => l.insuranceLine === this.newLine)) return;
    this.svc.addApplicableLine(this.treatyId, this.newLine).subscribe({
      next: (l) => { this.applicableLines = [...this.applicableLines, l]; },
      error: (err) => { this.errorMessage = err?.error?.detail || 'Line add failed'; },
    });
  }

  removeLine(l: TreatyApplicableLine): void {
    if (!this.treatyId) return;
    this.svc.removeApplicableLine(this.treatyId, l.insuranceLine).subscribe({
      next: () => { this.applicableLines = this.applicableLines.filter(x => x.insuranceLine !== l.insuranceLine); },
      error: (err) => { this.errorMessage = err?.error?.detail || 'Line remove failed'; },
    });
  }

  addRule(): void {
    if (!this.treatyId || !this.newRuleId.trim()) return;
    this.svc.addCessionRule(this.treatyId, this.newRuleId.trim(), true).subscribe({
      next: (r) => { this.cessionRules = [...this.cessionRules, r]; this.newRuleId = ''; },
      error: (err) => { this.errorMessage = err?.error?.detail || 'Rule link failed'; },
    });
  }

  toggleRule(r: CessionRuleLink): void {
    if (!this.treatyId) return;
    this.svc.toggleCessionRule(this.treatyId, r.id, !r.enabled).subscribe({
      next: (updated) => { this.cessionRules = this.cessionRules.map(x => x.id === r.id ? updated : x); },
      error: (err) => { this.errorMessage = err?.error?.detail || 'Rule toggle failed'; },
    });
  }

  removeRule(r: CessionRuleLink): void {
    if (!this.treatyId) return;
    if (!confirm('Unlink this cession rule from the treaty?')) return;
    this.svc.deleteCessionRule(this.treatyId, r.id).subscribe({
      next: () => { this.cessionRules = this.cessionRules.filter(x => x.id !== r.id); },
      error: (err) => { this.errorMessage = err?.error?.detail || 'Rule unlink failed'; },
    });
  }

  private emptyHeader(): CreateTreatyPayload {
    return {
      treatyRef: '',
      treatyType: 'QUOTA_SHARE',
      declaredCurrency: 'USD',
      inceptionDate: '',
      expiryDate: '',
      aggregateLimit: null,
      aggregateLimitCurrency: null,
      expectedAnnualPremium: null,
      producerId: null,
      producerRef: null,
    };
  }

  private emptyLayer(): UpsertTreatyLayerPayload {
    return {
      layerOrder: (this.layers?.length ?? 0) + 1,
      retention: 0,
      layerLimit: 0,
      layerCurrency: this.treaty?.declaredCurrency ?? this.header.declaredCurrency ?? 'USD',
      rate: 0,
      reinstatementCount: 0,
    };
  }

  private emptyParticipant(): UpsertTreatyParticipantPayload {
    return { reinsurerId: '', sharePct: 0, shareRole: 'FOLLOWING' };
  }

  private clearMessages(): void {
    this.errorMessage = null;
    this.successMessage = null;
  }
}
