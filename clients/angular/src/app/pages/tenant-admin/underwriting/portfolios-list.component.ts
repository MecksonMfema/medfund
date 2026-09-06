import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import {
  Ifrs17Portfolio,
  Ifrs17PortfolioService,
  InsuranceLine,
} from '../../../core/services/ifrs17-portfolio.service';
import { IconComponent } from '../../../shared/components/icon/icon.component';
import {
  DataTableComponent,
  TableAction,
  TableColumn,
} from '../../../shared/components/data-table/data-table.component';
import { SelectComponent, SelectOption } from '../../../shared/components/select/select.component';

interface PortfolioRow extends Ifrs17Portfolio {
  insuranceLineDisplay: string;
  statusLabel: 'Active' | 'Inactive';
}

@Component({
  selector: 'app-portfolios-list',
  standalone: true,
  imports: [
    CommonModule, FormsModule, RouterLink,
    IconComponent, DataTableComponent, SelectComponent,
  ],
  templateUrl: './portfolios-list.component.html',
  styleUrl: './portfolios-list.component.scss',
})
export class PortfoliosListComponent implements OnInit {
  rows: PortfolioRow[] = [];
  loading = false;
  errorMessage: string | null = null;
  successMessage: string | null = null;

  // Server-side flag (backend accepts includeInactive); the other filters
  // work client-side over the loaded rows.
  showInactive = false;
  selectedLine = '';
  searchTerm = '';

  private allRows: PortfolioRow[] = [];

  readonly insuranceLines: InsuranceLine[] = [
    'HEALTH', 'LIFE', 'FUNERAL', 'GROUP', 'TRAVEL', 'DISABILITY', 'VEHICLE', 'PROPERTY',
  ];

  readonly columns: TableColumn[] = [
    { key: 'name',                 label: 'Name',           sortable: true },
    { key: 'insuranceLineDisplay', label: 'Insurance line', sortable: true },
    { key: 'description',          label: 'Description' },
    { key: 'isActive',             label: 'Active',         type: 'boolean' },
  ];

  readonly actions: TableAction[] = [
    { label: 'Edit', icon: 'edit', color: 'default',
      visible: (row: PortfolioRow) => row.isActive,
      handler: (row: PortfolioRow) => this.editRow(row) },
    { label: 'Deactivate', icon: 'trash', color: 'danger',
      visible: (row: PortfolioRow) => row.isActive,
      handler: (row: PortfolioRow) => this.softDelete(row) },
  ];

  readonly statusOptions: SelectOption[] = [
    { value: 'false', label: 'Active only' },
    { value: 'true',  label: 'Include inactive' },
  ];

  get lineOptions(): SelectOption[] {
    return [
      { value: '', label: 'Any line' },
      { value: 'MISC', label: 'MISC (unassigned)' },
      ...this.insuranceLines.map(l => ({ value: l, label: l })),
    ];
  }

  constructor(private svc: Ifrs17PortfolioService, private router: Router) {}

  ngOnInit(): void { this.fetch(); }

  fetch(): void {
    this.loading = true;
    this.svc.list(this.showInactive).subscribe({
      next: (rows) => {
        this.allRows = rows.map(r => this.shape(r));
        this.applyClientFilters();
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Failed to load portfolios';
        this.allRows = [];
        this.rows = [];
        this.loading = false;
      },
    });
  }

  onServerFilterChange(value: string): void {
    // The Status select stores 'true' | 'false' as strings so the SelectComponent
    // ngModel round-trips cleanly; convert once here.
    this.showInactive = value === 'true';
    this.fetch();
  }

  onClientFilterChange(): void { this.applyClientFilters(); }

  onSearchInput(value: string): void {
    this.searchTerm = value ?? '';
    this.applyClientFilters();
  }

  clearFilters(): void {
    this.showInactive = false;
    this.selectedLine = '';
    this.searchTerm = '';
    this.fetch();
  }

  private applyClientFilters(): void {
    const q = this.searchTerm.trim().toLowerCase();
    this.rows = this.allRows.filter(r => {
      if (this.selectedLine) {
        if (this.selectedLine === 'MISC') {
          if (r.insuranceLine) return false;
        } else if (r.insuranceLine !== this.selectedLine) {
          return false;
        }
      }
      if (q) {
        const hay = `${r.name} ${r.description ?? ''} ${r.insuranceLineDisplay}`.toLowerCase();
        if (!hay.includes(q)) return false;
      }
      return true;
    });
  }

  editRow(row: PortfolioRow): void {
    this.router.navigate(['/tenant/admin/underwriting/portfolios', row.id, 'edit']);
  }

  softDelete(row: PortfolioRow): void {
    if (!confirm(`Deactivate portfolio "${row.name}"? Policies still referencing it stay linked.`)) return;
    this.svc.delete(row.id).subscribe({
      next: () => { this.successMessage = 'Portfolio deactivated'; this.fetch(); },
      error: (err) => {
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Deactivate failed';
      },
    });
  }

  get selectedStatusValue(): string {
    return this.showInactive ? 'true' : 'false';
  }

  private shape(r: Ifrs17Portfolio): PortfolioRow {
    return {
      ...r,
      insuranceLineDisplay: r.insuranceLine ?? 'MISC',
      statusLabel: r.isActive ? 'Active' : 'Inactive',
    };
  }
}
