import { CommonModule } from '@angular/common';
import {
  Component,
  EventEmitter,
  Input,
  OnChanges,
  OnInit,
  Output,
  SimpleChanges,
  forwardRef,
} from '@angular/core';
import { ControlValueAccessor, FormsModule, NG_VALUE_ACCESSOR } from '@angular/forms';
import { Observable, Subject, debounceTime, distinctUntilChanged, of, switchMap } from 'rxjs';
import { ContributionsService, Scheme } from '../../../core/services/contributions.service';
import { GroupsService, Group } from '../../../core/services/groups.service';
import { MembersService, Member } from '../../../core/services/members.service';
import { ProvidersService, Provider } from '../../../core/services/providers.service';

export type EntityKind = 'provider' | 'member' | 'group' | 'scheme' | 'beneficiary';

export interface EntityPickerSelection {
  id: string;
  label: string;
  sublabel?: string;
  /** Present only for kind='beneficiary'. Lets the caller distinguish
   *  a picked member from a picked dependant and, for dependants,
   *  route the sponsor's ID into the payload's memberId slot. */
  beneficiary?: BeneficiaryPick;
}

/** Extra fields the beneficiary typeahead threads through the picker
 *  so the caller can build (memberId, dependantId) without re-fetching. */
export interface BeneficiaryPick {
  kind: 'MEMBER' | 'DEPENDANT';
  /** The picked member's ID (kind=MEMBER) or the picked dependant's
   *  sponsor ID (kind=DEPENDANT). Goes on the claim's memberId slot. */
  memberId: string;
  /** The picked dependant's ID. Null for kind=MEMBER. Goes on the
   *  claim's dependantId slot. */
  dependantId: string | null;
  sponsorName?: string;
  sponsorMemberNumber?: string;
}

interface Suggestion {
  id: string;
  label: string;
  sublabel?: string;
  beneficiary?: BeneficiaryPick;
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
export class EntityPickerComponent implements OnInit, OnChanges, ControlValueAccessor {
  @Input() kind: EntityKind = 'provider';
  @Input() placeholder = '';
  @Input() disabled = false;
  @Input() value: string | null = null;
  /**
   * Human-readable label for the currently-selected ID. Lets edit forms
   * render the chip on first paint instead of an empty input — the parent
   * passes the entity name it already loaded (e.g. group.name) alongside
   * the id, so the picker doesn't have to lookup-by-id itself.
   */
  @Input() prefillLabel: string | null = null;
  @Input() prefillSublabel: string | null = null;
  /**
   * Suggestions shown the moment the picker gains focus, before the user
   * types anything. Lets the host pre-load a short list (typical: top N
   * by name) so a "blank" picker is still useful — e.g. the age-groups
   * page shows the first 5 schemes so the operator can pick one without
   * having to know its name.
   *
   * Leave empty (default) to keep the original behaviour: nothing shows
   * until the user starts typing.
   */
  @Input() initialSuggestions: EntityPickerSelection[] = [];
  @Output() valueChange = new EventEmitter<string | null>();
  @Output() selected = new EventEmitter<EntityPickerSelection | null>();

  query = '';
  matches: Suggestion[] = [];
  searching = false;
  picked: Suggestion | null = null;
  showMatches = false;
  /**
   * True between the user clicking "Change" and either making a new pick
   * or blurring out. While editing, {@link picked}/{@link value} stay
   * intact so the host keeps the previous selection until a replacement
   * is chosen — closes the "click Change → table empties" gap.
   */
  editing = false;

  /** Suggestions actually rendered in the popup — either the user's search
   *  matches (when a query is present) or the host-supplied
   *  {@link initialSuggestions} (when the field is empty but focused). */
  get visibleSuggestions(): Suggestion[] {
    if (this.query.trim()) return this.matches;
    return this.initialSuggestions;
  }

  private query$ = new Subject<string>();
  private onChange: (value: string | null) => void = () => {};
  private onTouched: () => void = () => {};

  constructor(
    private providersService: ProvidersService,
    private membersService: MembersService,
    private groupsService: GroupsService,
    private contributionsService: ContributionsService,
  ) {}

  ngOnChanges(changes: SimpleChanges): void {
    // Whenever the parent supplies a value + prefillLabel pair (typical for
    // edit forms loading an existing entity), reflect it in the picker UI.
    if (('value' in changes || 'prefillLabel' in changes) && this.value && this.prefillLabel) {
      this.picked = { id: this.value, label: this.prefillLabel, sublabel: this.prefillSublabel ?? undefined };
      this.query = this.prefillLabel;
    }
    if ('value' in changes && !this.value) {
      this.picked = null;
      this.query = '';
    }
  }

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
    this.editing = false;
    this.onChange(s.id);
    this.valueChange.emit(s.id);
    this.selected.emit({ id: s.id, label: s.label, sublabel: s.sublabel, beneficiary: s.beneficiary });
    this.onTouched();
  }

  /**
   * Enter edit mode without dropping the current pick. The chip is
   * swapped for the search input (focused, with the current label pre-
   * filled so it's discoverable), but {@link value} and {@link picked}
   * stay set — so the host keeps treating the previous selection as
   * active until the user explicitly picks a replacement.
   */
  startEditing(): void {
    if (this.disabled) return;
    this.editing = true;
    this.query = this.picked?.label ?? '';
    this.showMatches = true;
  }

  /**
   * Clear the picker entirely — drops the current pick and emits null.
   * Currently only used by the host via reactive-forms writeValue(null);
   * the "Change" button no longer calls this (it uses startEditing()).
   */
  clear(): void {
    this.picked = null;
    this.query = '';
    this.matches = [];
    this.editing = false;
    this.value = null;
    this.onChange(null);
    this.valueChange.emit(null);
    this.selected.emit(null);
  }

  onBlur(): void {
    // Defer hiding so a click on a suggestion still registers.
    setTimeout(() => {
      this.showMatches = false;
      // If the user opened edit mode but blurred out without picking a
      // replacement, restore the chip view — picked / value were never
      // touched, so the host is already in the right place.
      if (this.editing) {
        this.editing = false;
        this.query = this.picked?.label ?? '';
      }
    }, 150);
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
      case 'scheme':
        return this.contributionsService.searchSchemes(term).pipe(
          switchMap(rows => of<Suggestion[]>(
            rows.map((s: Scheme) => ({
              id: s.id,
              label: s.name,
              sublabel: s.schemeType || undefined,
            })),
          )),
        );
      case 'beneficiary':
        return this.membersService.searchBeneficiaries(term).pipe(
          switchMap(rows => of<Suggestion[]>(
            rows.map(b => ({
              id: b.id,
              label: `${b.firstName} ${b.lastName}`.trim(),
              // The badge in the sublabel is the operator's cue for
              // whether this hit is a primary or a dependant — matches
              // the mockup we agreed on ("MEM · MBR-000123" vs
              // "DEP of MBR-000201 — Tapiwa Zulu").
              sublabel: b.kind === 'MEMBER'
                ? `MEM · ${b.memberNumber ?? ''}`
                : `DEP · ${b.memberNumber ?? ''}${b.sponsorMemberNumber ? ' — ' + b.sponsorMemberNumber : ''}`
                    + (b.sponsorName ? ` (${b.sponsorName})` : ''),
              beneficiary: {
                kind: b.kind,
                memberId: b.kind === 'MEMBER' ? b.id : (b.sponsorId ?? b.id),
                dependantId: b.kind === 'DEPENDANT' ? b.id : null,
                sponsorName: b.sponsorName,
                sponsorMemberNumber: b.sponsorMemberNumber,
              },
            })),
          )),
        );
    }
  }

  private defaultPlaceholder(): string {
    switch (this.kind) {
      case 'provider':     return 'Search by provider name…';
      case 'member':       return 'Search by member name…';
      case 'group':        return 'Search by group name…';
      case 'scheme':       return 'Search by scheme name…';
      case 'beneficiary':  return 'Search member or dependant…';
    }
  }
}
