import {
  Component,
  ElementRef,
  EventEmitter,
  HostListener,
  Input,
  OnInit,
  Output,
  ViewChild,
  forwardRef,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule, ControlValueAccessor, NG_VALUE_ACCESSOR } from '@angular/forms';
import { IconComponent } from '../icon/icon.component';

/**
 * One row in a {@link SelectComponent}'s dropdown.
 *
 * <p>{@code group} is used by {@link SelectComponent#groupedOptions} to render
 * section headings — set the same string on every option that belongs to a
 * group and leave it undefined on flat lists. Items are kept in the order
 * the host provides; we do not re-sort within a group.
 */
export interface SelectOption {
  value: string | number | null;
  label: string;
  description?: string;
  disabled?: boolean;
  group?: string;
}

/**
 * Platform-wide single-select with a custom dropdown panel — the API surface
 * mirrors a native &lt;select&gt; (works with {@code [(ngModel)]} and reactive
 * forms via {@link ControlValueAccessor}) but the trigger and panel are fully
 * styleable and follow the app palette.
 *
 * <p>Comparable to the look of Material UI / Ant Design / Chakra Selects:
 * rounded trigger with palette-aware focus ring, animated rounded popup with
 * soft shadow, hover + selected states, optional in-popup search, optional
 * grouping with section labels, keyboard navigation, click-outside to close.
 *
 * <p>Replaces inline {@code &lt;select class="field-control"&gt;} usage so
 * dropdown panels look consistent across the platform — every form picks up
 * the same look without per-page CSS.
 */
@Component({
  selector: 'app-select',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent],
  templateUrl: './select.component.html',
  styleUrl: './select.component.scss',
  providers: [{
    provide: NG_VALUE_ACCESSOR,
    useExisting: forwardRef(() => SelectComponent),
    multi: true,
  }],
})
export class SelectComponent implements ControlValueAccessor, OnInit {
  @Input() options: SelectOption[] = [];
  @Input() placeholder = 'Select…';
  /** When true, an in-popup search input filters options by label substring. */
  @Input() searchable = false;
  @Input() searchPlaceholder = 'Search…';
  /**
   * Threshold (number of options) above which {@link searchable} is forced
   * on regardless of the explicit input — long lists are unusable without a
   * filter, so we auto-enable rather than relying on every caller to opt in.
   */
  @Input() autoSearchThreshold = 8;
  @Input() disabled = false;
  @Input() emptyText = 'No options';
  /**
   * Optional size hint applied to the trigger. {@code 'sm'} matches the
   * data-table toolbar density; default matches form fields. Other variants
   * can be added later by extending the SCSS modifier.
   */
  @Input() size: 'sm' | 'md' = 'md';
  /** Emitted whenever the user picks an option (in addition to the standard
   *  {@code (ngModelChange)} the host already gets via ControlValueAccessor). */
  @Output() selectionChange = new EventEmitter<any>();

  @ViewChild('searchInput') private searchInput?: ElementRef<HTMLInputElement>;

  open = false;
  value: any = null;
  searchTerm = '';
  highlightedIndex = -1;

  private onChange: (val: any) => void = () => {};
  private onTouched: () => void = () => {};

  constructor(private host: ElementRef<HTMLElement>) {}

  ngOnInit(): void {
    if (this.options.length >= this.autoSearchThreshold) {
      this.searchable = true;
    }
  }

  // ── ControlValueAccessor ─────────────────────────────────────────────────
  writeValue(val: any): void { this.value = val; }
  registerOnChange(fn: (val: any) => void): void { this.onChange = fn; }
  registerOnTouched(fn: () => void): void { this.onTouched = fn; }
  setDisabledState(isDisabled: boolean): void { this.disabled = isDisabled; }

  // ── Derived state ────────────────────────────────────────────────────────

  get selectedOption(): SelectOption | undefined {
    return this.options.find(o => o.value === this.value);
  }

  get selectedLabel(): string {
    return this.selectedOption?.label ?? '';
  }

  get filteredOptions(): SelectOption[] {
    if (!this.searchable || !this.searchTerm) return this.options;
    const term = this.searchTerm.toLowerCase();
    return this.options.filter(o =>
      o.label.toLowerCase().includes(term) ||
      (o.description?.toLowerCase().includes(term) ?? false),
    );
  }

  /**
   * Filtered options bucketed by their {@code group} field, preserving the
   * order in which groups first appear so the host stays in control of
   * sequencing (we never alphabetise behind their back).
   */
  get groupedOptions(): { group: string | null; items: SelectOption[] }[] {
    const order: (string | null)[] = [];
    const buckets = new Map<string | null, SelectOption[]>();
    for (const opt of this.filteredOptions) {
      const key = opt.group ?? null;
      if (!buckets.has(key)) {
        buckets.set(key, []);
        order.push(key);
      }
      buckets.get(key)!.push(opt);
    }
    return order.map(g => ({ group: g, items: buckets.get(g)! }));
  }

  // ── User interaction ─────────────────────────────────────────────────────

  toggle(): void {
    if (this.disabled) return;
    this.open = !this.open;
    if (this.open) {
      this.searchTerm = '';
      this.highlightedIndex = this.filteredOptions.findIndex(o => o.value === this.value);
      if (this.searchable) {
        // Defer until after the panel renders so the ViewChild resolves.
        setTimeout(() => this.searchInput?.nativeElement.focus(), 0);
      }
    } else {
      this.onTouched();
    }
  }

  close(): void {
    if (!this.open) return;
    this.open = false;
    this.searchTerm = '';
    this.onTouched();
  }

  select(option: SelectOption): void {
    if (option.disabled) return;
    this.value = option.value;
    this.onChange(option.value);
    this.selectionChange.emit(option.value);
    this.close();
  }

  // ── Keyboard + outside-click ─────────────────────────────────────────────

  @HostListener('document:click', ['$event'])
  onDocumentClick(ev: MouseEvent): void {
    if (this.open && !this.host.nativeElement.contains(ev.target as Node)) {
      this.close();
    }
  }

  @HostListener('keydown', ['$event'])
  onKeydown(ev: KeyboardEvent): void {
    if (this.disabled) return;
    if (!this.open) {
      if (ev.key === 'Enter' || ev.key === ' ' || ev.key === 'ArrowDown') {
        ev.preventDefault();
        this.toggle();
      }
      return;
    }
    if (ev.key === 'Escape' || ev.key === 'Tab') {
      this.close();
      return;
    }
    const opts = this.filteredOptions;
    if (ev.key === 'ArrowDown') {
      ev.preventDefault();
      this.highlightedIndex = Math.min(this.highlightedIndex + 1, opts.length - 1);
    } else if (ev.key === 'ArrowUp') {
      ev.preventDefault();
      this.highlightedIndex = Math.max(this.highlightedIndex - 1, 0);
    } else if (ev.key === 'Enter') {
      ev.preventDefault();
      const opt = opts[this.highlightedIndex];
      if (opt) this.select(opt);
    }
  }

  /**
   * Flat list index of an option among {@link filteredOptions} — lets the
   * template highlight the same item the keyboard handler is tracking,
   * even though the template iterates groups.
   */
  flatIndex(opt: SelectOption): number {
    return this.filteredOptions.indexOf(opt);
  }
}
