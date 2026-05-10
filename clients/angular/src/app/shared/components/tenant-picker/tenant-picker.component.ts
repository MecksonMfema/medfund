import { CommonModule } from '@angular/common';
import {
  Component,
  EventEmitter,
  Input,
  OnInit,
  Output,
  forwardRef,
} from '@angular/core';
import { ControlValueAccessor, FormsModule, NG_VALUE_ACCESSOR } from '@angular/forms';
import { AdminService, Tenant } from '../../../core/services/admin.service';

interface Suggestion {
  id: string;
  label: string;
  sublabel?: string;
}

/**
 * Tenant typeahead picker for the platform admin. Cached client-side so
 * typing is responsive and we don't ping the API on every keystroke.
 *
 * The {@code "All tenants"} option emits {@code null} — that's the
 * default state. Use it as a `[(value)]` two-way bind on a string id
 * (or null for the cross-tenant view).
 */
@Component({
  selector: 'app-tenant-picker',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './tenant-picker.component.html',
  styleUrl: './tenant-picker.component.scss',
  providers: [
    { provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => TenantPickerComponent), multi: true },
  ],
})
export class TenantPickerComponent implements OnInit, ControlValueAccessor {
  @Input() placeholder = 'All tenants';
  @Input() disabled = false;
  @Input() value: string | null = null;
  @Output() valueChange = new EventEmitter<string | null>();

  query = '';
  showMatches = false;
  loading = false;
  errorMessage: string | null = null;
  picked: Suggestion | null = null;

  /** Full tenant list, fetched once. The picker filters this in-memory. */
  private tenants: Tenant[] = [];

  private onChange: (value: string | null) => void = () => {};
  private onTouched: () => void = () => {};

  constructor(private admin: AdminService) {}

  ngOnInit(): void {
    this.loading = true;
    this.admin.getTenants({ size: 500 }).subscribe({
      next: (page) => {
        this.tenants = page.content;
        this.loading = false;
        this.syncPickedFromValue();
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load tenants';
        this.loading = false;
      },
    });
  }

  // ── ControlValueAccessor ────────────────────────────────────────────────

  writeValue(value: string | null): void {
    this.value = value || null;
    this.syncPickedFromValue();
  }

  registerOnChange(fn: (value: string | null) => void): void { this.onChange = fn; }
  registerOnTouched(fn: () => void): void { this.onTouched = fn; }
  setDisabledState(isDisabled: boolean): void { this.disabled = isDisabled; }

  // ── User interactions ──────────────────────────────────────────────────

  filteredTenants(): Tenant[] {
    const q = this.query.trim().toLowerCase();
    if (!q) return this.tenants;
    return this.tenants.filter(t =>
      t.name?.toLowerCase().includes(q) ||
      t.slug?.toLowerCase().includes(q) ||
      (t.domain && t.domain.toLowerCase().includes(q)),
    );
  }

  pick(t: Tenant): void {
    this.picked = { id: t.id, label: t.name, sublabel: t.slug };
    this.value = t.id;
    this.query = '';
    this.showMatches = false;
    this.onChange(t.id);
    this.valueChange.emit(t.id);
    this.onTouched();
  }

  clear(): void {
    this.picked = null;
    this.query = '';
    this.value = null;
    this.showMatches = false;
    this.onChange(null);
    this.valueChange.emit(null);
    this.onTouched();
  }

  onBlur(): void {
    setTimeout(() => { this.showMatches = false; }, 150);
    this.onTouched();
  }

  // ── Internals ──────────────────────────────────────────────────────────

  private syncPickedFromValue(): void {
    if (!this.value) {
      this.picked = null;
      return;
    }
    const match = this.tenants.find(t => t.id === this.value);
    this.picked = match
      ? { id: match.id, label: match.name, sublabel: match.slug }
      : { id: this.value, label: 'Unknown tenant', sublabel: this.value.substring(0, 8) };
  }
}
