import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import {
  ClaimsConfigService,
  CreateTariffCodePayload,
  PageResponse,
  TariffCodeRow,
  TariffSchedule,
} from '../../../../core/services/claims-config.service';
import { TariffCategoriesService, TariffCategory } from '../../../../core/services/tariff-categories.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';
import { DataTableComponent, TableColumn } from '../../../../shared/components/data-table/data-table.component';
import { CurrencyFormatPipe } from '../../../../shared/pipes/currency-format.pipe';
import { ToastService } from '../../../../shared/components/toast/toast.service';
import { extractErrorMessage } from '../../../../core/util/http-errors';

@Component({
  selector: 'app-tariff-codes-list',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    RouterLink,
    IconComponent,
    SelectComponent,
    DataTableComponent,
    CurrencyFormatPipe,
  ],
  templateUrl: './tariff-codes-list.component.html',
  styleUrl: './tariff-codes-list.component.scss',
})
export class TariffCodesListComponent implements OnInit {
  scheduleId: string | null = null;
  schedule: TariffSchedule | null = null;

  // Paginated rows — one page's worth at a time.
  rows: TariffCodeRow[] = [];
  totalCount = 0;
  totalPages = 1;
  page = 1;
  pageSize = 50;
  sortKey = 'code';
  sortDirection: 'asc' | 'desc' = 'asc';
  searchTerm = '';

  loading = false;
  saving = false;

  // Inline-add form state.
  showForm = false;
  draft: CreateTariffCodePayload = {
    scheduleId: '',
    code: '',
    description: '',
    categoryId: '',
    unitPrice: '',
    currencyCode: 'USD',
    requiresPreAuth: false,
  };

  /** V063 — populated on-demand from the tariff_categories catalogue.
   *  Only fetched when the add form is opened, so a categories outage
   *  never blocks the codes table. */
  categories: TariffCategory[] = [];
  categoryOptions: SelectOption[] = [];
  private categoriesLoaded = false;

  readonly columns: TableColumn[] = [
    { key: 'code',            label: 'Code',        sortable: true },
    { key: 'description',     label: 'Description', sortable: true },
    { key: 'categoryLabel',   label: 'Category',    sortable: true },
    { key: 'unitPrice',       label: 'Unit price',  sortable: true, type: 'currency' },
    { key: 'requiresPreAuth', label: 'Pre-auth',    sortable: true, type: 'boolean' },
  ];

  readonly currencyOptions: SelectOption[] = [
    { value: 'USD', label: 'USD' },
    { value: 'ZWL', label: 'ZWL' },
    { value: 'ZAR', label: 'ZAR' },
  ];

  constructor(private config: ClaimsConfigService,
              private categoriesService: TariffCategoriesService,
              private route: ActivatedRoute,
              private toast: ToastService) {}

  ngOnInit(): void {
    this.scheduleId = this.route.snapshot.paramMap.get('scheduleId');
    if (!this.scheduleId) return;
    this.draft.scheduleId = this.scheduleId;

    // Load schedule metadata once: 404s land in a clearer message than
    // the generic "Failed to load tariff codes" toast.
    this.config.getSchedule(this.scheduleId).subscribe({
      next: (s) => { this.schedule = s; },
      error: (err) => {
        if (err?.status === 404) {
          this.toast.error('This tariff schedule no longer exists. Head back to the schedules list to pick another.');
        } else {
          this.toast.error(extractErrorMessage(err, 'Failed to load tariff schedule'));
        }
      },
    });

    this.fetchPage();
  }

  // ── Pagination + search + sort → server-side load ─────────────────────
  fetchPage(): void {
    if (!this.scheduleId) return;
    this.loading = true;
    this.config.listCodesPaged({
      scheduleId: this.scheduleId,
      q: this.searchTerm || undefined,
      sortKey: this.sortKey,
      sortDirection: this.sortDirection,
      page: this.page - 1,
      size: this.pageSize,
    }).subscribe({
      next: (resp: PageResponse<TariffCodeRow>) => {
        this.rows = resp.content;
        this.totalCount = resp.total;
        this.totalPages = resp.totalPages;
        this.loading = false;
      },
      error: (err) => {
        this.toast.error(extractErrorMessage(err, 'Failed to load tariff codes'));
        this.rows = [];
        this.totalCount = 0;
        this.totalPages = 1;
        this.loading = false;
      },
    });
  }

  onPageChange(page: number): void {
    this.page = page;
    this.fetchPage();
  }

  onSearchChange(term: string): void {
    this.searchTerm = term;
    this.page = 1;
    this.fetchPage();
  }

  onSortChange(evt: { key: string; direction: 'asc' | 'desc' }): void {
    this.sortKey = evt.key;
    this.sortDirection = evt.direction;
    this.page = 1;
    this.fetchPage();
  }

  // ── Inline add form ───────────────────────────────────────────────────
  toggleForm(): void {
    this.showForm = !this.showForm;
    if (this.showForm) {
      this.ensureCategoriesLoaded();
    } else {
      this.resetDraft();
    }
  }

  /** Lazy-loads the categories catalogue the first time the add form
   *  opens. Failure is non-fatal: the dropdown just stays empty and
   *  the operator sees a toast. Codes list remains usable. */
  private ensureCategoriesLoaded(): void {
    if (this.categoriesLoaded) return;
    this.categoriesService.list(true).subscribe({
      next: (rows) => {
        this.categories = rows;
        this.categoryOptions = rows.map(c => ({
          value: c.id,
          label: c.label + (c.isCapOnly ? ' · cap-only' : ''),
        }));
        this.categoriesLoaded = true;
      },
      error: () => {
        this.toast.error('Categories catalogue is unavailable: code creation is disabled until it comes back.');
      },
    });
  }

  submitDraft(): void {
    if (!this.draft.code.trim() || !this.draft.description.trim() || !this.draft.unitPrice) {
      this.toast.warning('Code, description and unit price are required');
      return;
    }
    if (!this.draft.categoryId) {
      this.toast.warning('Category is required');
      return;
    }
    this.saving = true;
    this.config.createCode({
      scheduleId: this.scheduleId!,
      code: this.draft.code.trim().toUpperCase(),
      description: this.draft.description.trim(),
      categoryId: this.draft.categoryId,
      unitPrice: this.draft.unitPrice,
      currencyCode: this.draft.currencyCode || 'USD',
      requiresPreAuth: this.draft.requiresPreAuth,
    }).subscribe({
      next: () => {
        this.saving = false;
        this.resetDraft();
        // Reload from the server so the new row lands on the current
        // sort + filter view; keeps categoryLabel populated correctly.
        this.page = 1;
        this.fetchPage();
      },
      error: (err) => {
        this.saving = false;
        this.toast.error(extractErrorMessage(err, 'Save failed'));
      },
    });
  }

  private resetDraft(): void {
    this.draft = {
      scheduleId: this.scheduleId!,
      code: '',
      description: '',
      categoryId: '',
      unitPrice: '',
      currencyCode: 'USD',
      requiresPreAuth: false,
    };
  }
}
