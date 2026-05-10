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
import { Observable, Subject, debounceTime, distinctUntilChanged, of, switchMap } from 'rxjs';
import { GroupsService, Group } from '../../../core/services/groups.service';
import { MembersService, Member } from '../../../core/services/members.service';
import { ProvidersService, Provider } from '../../../core/services/providers.service';

export type EntityKind = 'provider' | 'member' | 'group';

export interface EntityPickerSelection {
  id: string;
  label: string;
  sublabel?: string;
}

interface Suggestion {
  id: string;
  label: string;
  sublabel?: string;
}

/**
 * Debounced search-picker for finance forms. Replaces raw UUID text inputs
 * with a typeahead backed by the relevant user-service /search endpoint.
 *
 * Usage:
 *
 *   <app-entity-picker
 *     kind="provider"
 *     [(value)]="form.providerId"
 *     placeholder="Search by provider name…">
 *   </app-entity-picker>
 *
 * The value is a UUID string (or empty); the user never types it directly.
 */
@Component({
  selector: 'app-entity-picker',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './entity-picker.component.html',
  styleUrl: './entity-picker.component.scss',
  providers: [
    { provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => EntityPickerComponent), multi: true },
  ],
})
export class EntityPickerComponent implements OnInit, ControlValueAccessor {
  @Input() kind: EntityKind = 'provider';
  @Input() placeholder = '';
  @Input() disabled = false;
  @Input() value: string | null = null;
  @Output() valueChange = new EventEmitter<string | null>();
  @Output() selected = new EventEmitter<EntityPickerSelection | null>();

  query = '';
  matches: Suggestion[] = [];
  searching = false;
  picked: Suggestion | null = null;
  showMatches = false;

  private query$ = new Subject<string>();
  private onChange: (value: string | null) => void = () => {};
  private onTouched: () => void = () => {};

  constructor(
    private providersService: ProvidersService,
    private membersService: MembersService,
    private groupsService: GroupsService,
  ) {}

  ngOnInit(): void {
    if (!this.placeholder) {
      this.placeholder = this.defaultPlaceholder();
    }

    this.query$
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        switchMap((q) => {
          const trimmed = q.trim();
          if (!trimmed) {
            this.searching = false;
            return of<Suggestion[]>([]);
          }
          this.searching = true;
          return this.search(trimmed);
        }),
      )
      .subscribe({
        next: (rows) => { this.matches = rows; this.searching = false; },
        error: () => { this.matches = []; this.searching = false; },
      });
  }

  // ── ControlValueAccessor ────────────────────────────────────────────────

  writeValue(value: string | null): void {
    this.value = value || null;
    if (!this.value) {
      this.picked = null;
      this.query = '';
    }
  }

  registerOnChange(fn: (value: string | null) => void): void { this.onChange = fn; }
  registerOnTouched(fn: () => void): void { this.onTouched = fn; }
  setDisabledState(isDisabled: boolean): void { this.disabled = isDisabled; }

  // ── User interactions ──────────────────────────────────────────────────

  onQueryChange(): void {
    this.showMatches = true;
    this.query$.next(this.query);
  }

  pick(s: Suggestion): void {
    this.picked = s;
    this.query = s.label;
    this.value = s.id;
    this.matches = [];
    this.showMatches = false;
    this.onChange(s.id);
    this.valueChange.emit(s.id);
    this.selected.emit({ id: s.id, label: s.label, sublabel: s.sublabel });
    this.onTouched();
  }

  clear(): void {
    this.picked = null;
    this.query = '';
    this.matches = [];
    this.value = null;
    this.onChange(null);
    this.valueChange.emit(null);
    this.selected.emit(null);
  }

  onBlur(): void {
    // Defer hiding so a click on a suggestion still registers.
    setTimeout(() => { this.showMatches = false; }, 150);
    this.onTouched();
  }

  // ── Internals ──────────────────────────────────────────────────────────

  private search(term: string): Observable<Suggestion[]> {
    switch (this.kind) {
      case 'provider':
        return this.providersService.query({ q: term, size: 10 }).pipe(
          switchMap(page => of<Suggestion[]>(
            page.content.map((p: Provider) => ({
              id: p.id,
              label: p.name,
              sublabel: p.registrationNumber || p.specialty || undefined,
            })),
          )),
        );
      case 'member':
        return this.membersService.searchByName(term).pipe(
          switchMap(rows => of<Suggestion[]>(
            rows.map((m: Member) => ({
              id: m.id,
              label: `${m.firstName} ${m.lastName}`.trim(),
              sublabel: m.memberNumber,
            })),
          )),
        );
      case 'group':
        return this.groupsService.search(term).pipe(
          switchMap(rows => of<Suggestion[]>(
            rows.map((g: Group) => ({
              id: g.id,
              label: g.name,
              sublabel: g.registrationNumber || undefined,
            })),
          )),
        );
    }
  }

  private defaultPlaceholder(): string {
    switch (this.kind) {
      case 'provider': return 'Search by provider name…';
      case 'member': return 'Search by member name…';
      case 'group': return 'Search by group name…';
    }
  }
}
