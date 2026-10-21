import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  CreateFacultativeCessionPayload,
  FacultativeCandidateRow,
  InsuranceLine,
  ReinsuranceService,
  Treaty,
} from '../../../../core/services/reinsurance.service';
import {
  DataTableComponent,
  TableAction,
  TableColumn,
} from '../../../../shared/components/data-table/data-table.component';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';
import { ToastService } from '../../../../shared/components/toast/toast.service';
import { extractErrorMessage } from '../../../../core/util/http-errors';

/**
 * Cedable-claims tab of the merged Facultative page. Adjudicated claims
 * render as a standard data-table; clicking the row-level Cede action
 * opens a modal to draft the cession. Approve / commit lives on the
 * sibling Cession queue tab.
 */
@Component({
  selector: 'app-facultative-candidates-tab',
  standalone: true,
  imports: [CommonModule, FormsModule, DataTableComponent, SelectComponent],
  templateUrl: './facultative-candidates-tab.component.html',
  styleUrl: './facultative-candidates-tab.component.scss',
})
export class FacultativeCandidatesTabComponent implements OnInit {
  candidates: FacultativeCandidateRow[] = [];
  loading = false;

  minAmount = 10000;
  lineFilter: '' | InsuranceLine = '';

  activeTreaties: Treaty[] = [];
  treatiesLoading = false;

  selected: FacultativeCandidateRow | null = null;
  cedePayload: {
    treatyId: string;
    cededAmount: number | null;
    basisAmount: number | null;
    reason: string;
  } = { treatyId: '', cededAmount: null, basisAmount: null, reason: '' };
  cedeSubmitting = false;

  readonly lineOptions: SelectOption[] = [
    { value: '', label: 'All lines' },
    { value: 'HEALTH', label: 'Health' },
    { value: 'LIFE', label: 'Life' },
    { value: 'FUNERAL', label: 'Funeral' },
    { value: 'GROUP', label: 'Group' },
    { value: 'TRAVEL', label: 'Travel' },
    { value: 'DISABILITY', label: 'Disability' },
    { value: 'VEHICLE', label: 'Motor' },
    { value: 'PROPERTY', label: 'Property' },
  ];

  readonly columns: TableColumn[] = [
    { key: 'claimNumber',    label: 'Claim #',  sortable: false },
    { key: 'memberName',     label: 'Member',   sortable: false },
    { key: 'providerName',   label: 'Provider', sortable: false },
    { key: 'insuranceLine',  label: 'Line',     sortable: false, type: 'status' },
    { key: 'approvedAmount', label: 'Approved', sortable: false, type: 'currency' },
  ];

  readonly actions: TableAction[] = [
    {
      label: 'Cede',
      icon: 'external-link',
      color: 'default',
      handler: (row: FacultativeCandidateRow) => this.select(row),
    },
  ];

  constructor(private svc: ReinsuranceService, private toast: ToastService) {}

  ngOnInit(): void {
    this.fetchCandidates();
    this.fetchTreaties();
  }

  fetchCandidates(): void {
    this.loading = true;
    this.svc.listFacultativeCandidates(
      this.minAmount > 0 ? this.minAmount : undefined,
      this.lineFilter || undefined,
      0,
      100,
    ).subscribe({
      next: rows => {
        this.candidates = rows;
        this.loading = false;
      },
      error: err => {
        this.toast.error(extractErrorMessage(err, 'Failed to load facultative candidates'));
        this.candidates = [];
        this.loading = false;
      },
    });
  }

  private fetchTreaties(): void {
    this.treatiesLoading = true;
    this.svc.listTreaties(0, 200, 'ACTIVE').subscribe({
      next: page => {
        this.activeTreaties = page.content;
        this.treatiesLoading = false;
      },
      error: () => {
        this.activeTreaties = [];
        this.treatiesLoading = false;
      },
    });
  }

  onMinAmountChange(): void { this.fetchCandidates(); }
  onLineChange(): void { this.fetchCandidates(); }

  select(row: FacultativeCandidateRow): void {
    this.selected = row;
    this.cedePayload = {
      treatyId: '',
      cededAmount: null,
      basisAmount: row.approvedAmount,
      reason: '',
    };
  }

  clearSelection(): void {
    this.selected = null;
    this.cedePayload = { treatyId: '', cededAmount: null, basisAmount: null, reason: '' };
  }

  get treatyOptions(): SelectOption[] {
    const opts: SelectOption[] = [{ value: '', label: this.treatiesLoading ? 'Loading…' : 'Select a treaty' }];
    if (!this.selected) return opts;
    for (const t of this.activeTreaties) {
      opts.push({ value: t.id, label: `${t.treatyRef} (${t.treatyType}, ${t.declaredCurrency})` });
    }
    return opts;
  }

  submitCede(): void {
    if (!this.selected) return;
    if (!this.cedePayload.treatyId) {
      this.toast.warning('Select a treaty first.');
      return;
    }
    if (!this.cedePayload.cededAmount || this.cedePayload.cededAmount <= 0) {
      this.toast.warning('Ceded amount must be positive.');
      return;
    }
    if (!this.cedePayload.basisAmount || this.cedePayload.basisAmount <= 0) {
      this.toast.warning('Basis amount must be positive.');
      return;
    }
    const payload: CreateFacultativeCessionPayload = {
      claimId: this.selected.claimId,
      treatyId: this.cedePayload.treatyId,
      cededAmount: this.cedePayload.cededAmount,
      basisAmount: this.cedePayload.basisAmount,
      currencyCode: this.selected.currencyCode,
      reason: this.cedePayload.reason || undefined,
    };
    this.cedeSubmitting = true;
    this.svc.createFacultativeCession(this.selected.insuranceLine, payload).subscribe({
      next: cession => {
        this.toast.success(`Draft cession #${cession.id.substring(0, 8)} created: awaiting approval.`);
        this.cedeSubmitting = false;
        this.selected = null;
        this.fetchCandidates();
      },
      error: err => {
        this.toast.error(extractErrorMessage(err, 'Failed to create draft cession.'));
        this.cedeSubmitting = false;
      },
    });
  }
}
