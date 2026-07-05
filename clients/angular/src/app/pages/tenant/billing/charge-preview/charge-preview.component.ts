import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Observable, Subject, Subscription, interval, of, switchMap } from 'rxjs';
import { debounceTime, distinctUntilChanged } from 'rxjs/operators';
import {
  ChargePreviewLine,
  ChargePreviewResponse,
  ContributionsService,
  GroupOption,
} from '../../../../core/services/contributions.service';
import { CurrencyService, TenantCurrencyConfig } from '../../../../core/services/currency.service';
import { MembersService, Member } from '../../../../core/services/members.service';
import { TenantService, Tenant } from '../../../../core/services/tenant.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';
import { CurrencyFormatPipe } from '../../../../shared/pipes/currency-format.pipe';

type SubjectType = 'GROUP' | 'MEMBER';

interface TargetOption {
  id: string;
  label: string;
  sublabel?: string;
}

/**
 * Live projection of what the selected group or individual would owe in
 * the next billing cycle, itemised by member/dependant. Layout mirrors
 * /tenant/billing/ledger — page-header banner, subject-type tab strip,
 * single-line toolbar, then a statement-style breakdown table.
 *
 * <p>Auto-refetches every {@code REFRESH_MS} milliseconds when a subject
 * is picked, so future scheme upgrades, custom-price edits, or member
 * enrolments made in another tab surface here without a manual reload.
 * The visible "as of HH:MM:SS" stamp lets the operator confirm they're
 * looking at a live number.
 */
@Component({
  selector: 'app-charge-preview',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, SelectComponent, CurrencyFormatPipe],
  templateUrl: './charge-preview.component.html',
  styleUrl: './charge-preview.component.scss',
})
export class ChargePreviewComponent implements OnInit, OnDestroy {
  /**
   * Poll interval. 30s is a balance: fresh enough for "live movement"
   * without hammering the server on a page an operator may leave open
   * all day. Increase if a tenant complains about noise; decrease if
   * they say the number lags.
   */
  private static readonly REFRESH_MS = 30_000;

  activeTab: SubjectType = 'GROUP';
  showTabs = false;                       // suppressed unless membershipModel === 'BOTH'

  // ── Subject picker (typeahead) ────────────────────────────────────
  targetQuery = '';
  targetMatches: TargetOption[] = [];
  targetSearching = false;
  showMatches = false;
  selectedTarget: TargetOption | null = null;
  highlightIndex = -1;

  currencies: TenantCurrencyConfig[] = [];
  selectedCurrency = '';

  preview: ChargePreviewResponse | null = null;
  loading = false;
  errorMessage: string | null = null;

  private targetQuery$ = new Subject<string>();
  private subs: Subscription[] = [];

  get currencyOptions(): SelectOption[] {
    const options: SelectOption[] = this.currencies.map(c => ({
      value: c.currencyCode,
      label: `${c.currencyCode}${c.isDefault ? ' (default)' : ''}`,
    }));
    // "All currencies" is the wide default — a multi-currency tenant
    // often wants to see the full projection before narrowing.
    return [{ value: '', label: 'All currencies' }, ...options];
  }

  get totalKeys(): string[] {
    return this.preview ? Object.keys(this.preview.totals) : [];
  }

  constructor(
    private contributionsService: ContributionsService,
    private membersService: MembersService,
    private currencyService: CurrencyService,
    private tenantService: TenantService,
  ) {}

  ngOnInit(): void {
    // Tab visibility is driven by the tenant's membership model — a
    // GROUP_ONLY tenant should never see the Individual tab (and vice
    // versa) because that half of the resolver returns nothing for
    // their data model.
    this.subs.push(
      this.tenantService.tenant$.subscribe(t => this.configureTabsFrom(t)),
    );

    const tenantId = this.tenantService.getTenantId();
    if (!tenantId) {
      this.errorMessage = 'No active tenant context';
      return;
    }
    this.currencyService.listForTenant(tenantId).subscribe({
      next: (configs) => {
        this.currencies = configs.filter(c => c.isActive && c.isBillingCurrency);
        // Default to "All currencies" — pre-selecting one hides the
        // multi-currency case from the operator on first paint. They
        // can narrow if they want to.
        this.selectedCurrency = '';
      },
      error: (err) => { this.errorMessage = err?.error?.detail || 'Failed to load currencies'; },
    });

    // Debounced typeahead — 300ms window before the search fires. The
    // switchMap routes to members-search vs groups-search based on the
    // active tab, so switching tabs re-uses the same input.
    this.subs.push(
      this.targetQuery$.pipe(
        debounceTime(300),
        distinctUntilChanged(),
        switchMap(q => {
          const trimmed = q.trim();
          if (!trimmed) { this.targetSearching = false; return of<TargetOption[]>([]); }
          this.targetSearching = true;
          return this.search(trimmed);
        }),
      ).subscribe({
        next: (rows) => {
          this.targetMatches = rows;
          this.targetSearching = false;
          this.highlightIndex = -1;
        },
        error: (err) => {
          this.targetSearching = false;
          this.errorMessage = err?.error?.detail || 'Search failed';
        },
      }),
    );

    // Auto-refresh — every REFRESH_MS. Skips ticks when nothing is
    // picked or a manual refresh is already in flight; a piled-up
    // queue would double-charge the /charge-preview endpoint if the
    // operator wandered off with the tab open.
    this.subs.push(
      interval(ChargePreviewComponent.REFRESH_MS).subscribe(() => {
        if (this.selectedTarget && !this.loading) this.fetch();
      }),
    );
  }

  ngOnDestroy(): void {
    this.subs.forEach(s => s.unsubscribe());
  }

  // ── Membership-model tab wiring ───────────────────────────────────

  private configureTabsFrom(t: Tenant | null): void {
    const model = t?.membershipModel ?? 'BOTH';
    switch (model) {
      case 'INDIVIDUAL_ONLY':
        this.showTabs = false;
        this.activeTab = 'MEMBER';
        break;
      case 'GROUP_ONLY':
        this.showTabs = false;
        this.activeTab = 'GROUP';
        break;
      case 'BOTH':
      default:
        this.showTabs = true;
        // Default to GROUP tab — matches the semantic of the old page
        // this replaces (group-charge). Preserves operator muscle memory.
        if (!this.activeTab) this.activeTab = 'GROUP';
    }
  }

  selectTab(value: SubjectType): void {
    if (this.activeTab === value) return;
    this.activeTab = value;
    // Switching the target dimension invalidates the current pick +
    // preview — a group id can't stand in as a member id.
    this.clearTarget();
  }

  // ── Target typeahead ──────────────────────────────────────────────

  onTargetQueryChange(): void {
    this.showMatches = true;
    // If the user is editing after a pick, drop the current preview so
    // it doesn't linger with a mismatched label at the top.
    if (this.selectedTarget && this.targetQuery !== this.selectedTarget.label) {
      this.selectedTarget = null;
      this.preview = null;
    }
    this.targetQuery$.next(this.targetQuery);
  }

  onTargetFocus(): void {
    this.showMatches = true;
    if (this.targetMatches.length === 0) this.targetQuery$.next(this.targetQuery);
  }

  onTargetBlur(): void {
    // Defer so a mousedown on a suggestion completes before the popup
    // hides — same trick the ledger picker uses.
    setTimeout(() => (this.showMatches = false), 150);
  }

  pickTarget(opt: TargetOption): void {
    this.selectedTarget = opt;
    this.targetQuery = opt.label;
    this.showMatches = false;
    this.fetch();
  }

  clearTarget(): void {
    this.selectedTarget = null;
    this.targetQuery = '';
    this.targetMatches = [];
    this.preview = null;
  }

  onKeydown(event: KeyboardEvent): void {
    if (!this.showMatches || this.targetMatches.length === 0) return;
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      this.highlightIndex = (this.highlightIndex + 1) % this.targetMatches.length;
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      this.highlightIndex =
        this.highlightIndex <= 0 ? this.targetMatches.length - 1 : this.highlightIndex - 1;
    } else if (event.key === 'Enter' && this.highlightIndex >= 0) {
      event.preventDefault();
      this.pickTarget(this.targetMatches[this.highlightIndex]);
    } else if (event.key === 'Escape') {
      this.showMatches = false;
    }
  }

  private search(term: string): Observable<TargetOption[]> {
    if (this.activeTab === 'GROUP') {
      return new Observable<TargetOption[]>(sub => {
        this.contributionsService.searchGroups(term).subscribe({
          next: (rows) => sub.next(rows.map(this.groupToOption)),
          error: (err) => sub.error(err),
          complete: () => sub.complete(),
        });
      });
    }
    return new Observable<TargetOption[]>(sub => {
      this.membersService.searchByName(term).subscribe({
        next: (rows) => sub.next(rows.map(this.memberToOption).filter(o => !!o)),
        error: (err) => sub.error(err),
        complete: () => sub.complete(),
      });
    });
  }

  private groupToOption(g: GroupOption): TargetOption {
    return {
      id: g.id,
      label: g.name,
      sublabel: g.registrationNumber || undefined,
    };
  }

  /**
   * Members attached to a group are billed through the group's liaison,
   * so a per-member charge preview would double-count with the group
   * projection. Match the payer-picker guard on the transactions page
   * — grouped members never appear in this search (memory:
   * feedback_grouped_members_cannot_pay).
   */
  private memberToOption(m: Member): TargetOption {
    if (m.groupId) return undefined as unknown as TargetOption;
    return {
      id: m.id,
      label: `${m.firstName} ${m.lastName}`.trim(),
      sublabel: m.memberNumber,
    };
  }

  // ── Filter changes ────────────────────────────────────────────────

  onCurrencyChange(): void {
    if (this.selectedTarget) this.fetch();
  }

  fetch(): void {
    if (!this.selectedTarget) return;
    this.loading = true;
    this.errorMessage = null;
    this.contributionsService.chargePreview(
      this.activeTab,
      this.selectedTarget.id,
      this.selectedCurrency || undefined,
    ).subscribe({
      next: (resp) => { this.preview = resp; this.loading = false; },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to compute charge preview';
        this.preview = null;
        this.loading = false;
      },
    });
  }

  // ── Presentation helpers ──────────────────────────────────────────

  asOfLabel(iso: string): string {
    try {
      const d = new Date(iso);
      return d.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit', second: '2-digit' });
    } catch { return '—'; }
  }

  trackLine(_i: number, line: ChargePreviewLine): string {
    return line.dependantId ?? line.memberId;
  }

  /**
   * True when a row starts a new household — the principal member's
   * row. Drives the {@code .household-boundary} class so a subtle
   * top-border visually separates families without cluttering the
   * table with headers. Backend already clusters by memberId + puts
   * MEMBER before DEPENDANT so we just have to detect the memberId
   * transition; the first row always counts as a boundary.
   */
  isHouseholdStart(index: number): boolean {
    if (!this.preview || index === 0) return true;
    const prev = this.preview.lines[index - 1];
    const curr = this.preview.lines[index];
    return prev.memberId !== curr.memberId;
  }
}
