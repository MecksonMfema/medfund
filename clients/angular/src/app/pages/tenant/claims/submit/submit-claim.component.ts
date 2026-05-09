import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { Subject, debounceTime, distinctUntilChanged, of, switchMap } from 'rxjs';
import {
  ClaimsService,
  ClaimLinePayload,
  SubmitClaimPayload,
  Claim,
} from '../../../../core/services/claims.service';
import {
  ClaimsConfigService,
  TariffCode,
  TariffSchedule,
} from '../../../../core/services/claims-config.service';
import { MembersService, Member } from '../../../../core/services/members.service';
import {
  ProvidersService,
  Provider,
} from '../../../../core/services/providers.service';
import {
  ContributionsService,
  Scheme,
} from '../../../../core/services/contributions.service';
import { CurrencyService, TenantCurrencyConfig } from '../../../../core/services/currency.service';
import { TenantService } from '../../../../core/services/tenant.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { CurrencyFormatPipe } from '../../../../shared/pipes/currency-format.pipe';

interface PickerOption {
  id: string;
  label: string;
  sublabel?: string;
}

interface LineDraft {
  tariffCode: string;
  description: string;
  quantity: number;
  unitPrice: string;
  modifierCodes: string;
}

@Component({
  selector: 'app-submit-claim',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent, CurrencyFormatPipe],
  templateUrl: './submit-claim.component.html',
  styleUrl: './submit-claim.component.scss',
})
export class SubmitClaimComponent implements OnInit {
  saving = false;
  errorMessage: string | null = null;
  successMessage: string | null = null;
  submittedClaim: Claim | null = null;
  verificationCode = '';
  verifying = false;

  // ── Member picker ────────────────────────────────────────────────────
  memberQuery = '';
  memberMatches: PickerOption[] = [];
  memberSearching = false;
  selectedMember: PickerOption | null = null;
  showMemberMatches = false;

  // ── Provider picker ──────────────────────────────────────────────────
  providerQuery = '';
  providerMatches: PickerOption[] = [];
  providerSearching = false;
  selectedProvider: PickerOption | null = null;
  showProviderMatches = false;

  // ── Reference data ───────────────────────────────────────────────────
  schemes: Scheme[] = [];
  schedules: TariffSchedule[] = [];
  currencies: TenantCurrencyConfig[] = [];

  // ── Form ─────────────────────────────────────────────────────────────
  form = {
    schemeId: '',
    serviceDate: new Date().toISOString().slice(0, 10),
    currencyCode: 'USD',
    diagnosisCodes: '',
    notes: '',
  };

  lines: LineDraft[] = [{ tariffCode: '', description: '', quantity: 1, unitPrice: '', modifierCodes: '' }];

  private memberQuery$ = new Subject<string>();
  private providerQuery$ = new Subject<string>();

  constructor(
    private claims: ClaimsService,
    private config: ClaimsConfigService,
    private membersService: MembersService,
    private providersService: ProvidersService,
    private contributions: ContributionsService,
    private currencyService: CurrencyService,
    private tenantService: TenantService,
    private router: Router,
  ) {}

  ngOnInit(): void {
    const tenant = this.tenantService.getTenant();
    if (tenant) {
      this.currencyService.listForTenant(tenant.id).subscribe({
        next: (rows) => {
          this.currencies = rows.filter(c => c.isActive);
          const def = this.currencies.find(c => c.isDefault);
          if (def) this.form.currencyCode = def.currencyCode;
        },
      });
    }

    this.contributions.getSchemes().subscribe({
      next: (rows) => { this.schemes = rows; },
    });

    this.config.listSchedules().subscribe({
      next: (rows) => { this.schedules = rows; },
    });

    // Wire member search.
    this.memberQuery$
      .pipe(
        debounceTime(350),
        distinctUntilChanged(),
        switchMap((q) => {
          if (!q.trim()) { this.memberSearching = false; return of<Member[]>([]); }
          this.memberSearching = true;
          return this.membersService.searchByName(q.trim());
        }),
      )
      .subscribe({
        next: (rows) => {
          this.memberMatches = rows.map(m => ({
            id: m.id,
            label: `${m.firstName} ${m.lastName}`.trim(),
            sublabel: m.memberNumber,
          }));
          this.memberSearching = false;
        },
        error: () => { this.memberMatches = []; this.memberSearching = false; },
      });

    // Wire provider search.
    this.providerQuery$
      .pipe(
        debounceTime(350),
        distinctUntilChanged(),
        switchMap((q) => {
          if (!q.trim()) { this.providerSearching = false; return of({ content: [] as Provider[] } as any); }
          this.providerSearching = true;
          return this.providersService.query({ q: q.trim(), size: 10 });
        }),
      )
      .subscribe({
        next: (page: any) => {
          const rows: Provider[] = page?.content ?? [];
          this.providerMatches = rows.map(p => ({
            id: p.id,
            label: p.name,
            sublabel: p.specialty || p.registrationNumber,
          }));
          this.providerSearching = false;
        },
        error: () => { this.providerMatches = []; this.providerSearching = false; },
      });
  }

  // ── Picker handlers ──────────────────────────────────────────────────
  onMemberQueryChange(): void { this.showMemberMatches = true; this.memberQuery$.next(this.memberQuery); }
  pickMember(o: PickerOption): void {
    this.selectedMember = o;
    this.memberQuery = o.label;
    this.showMemberMatches = false;
    this.memberMatches = [];
  }
  clearMember(): void { this.selectedMember = null; this.memberQuery = ''; }

  onProviderQueryChange(): void { this.showProviderMatches = true; this.providerQuery$.next(this.providerQuery); }
  pickProvider(o: PickerOption): void {
    this.selectedProvider = o;
    this.providerQuery = o.label;
    this.showProviderMatches = false;
    this.providerMatches = [];
  }
  clearProvider(): void { this.selectedProvider = null; this.providerQuery = ''; }

  // ── Line items ───────────────────────────────────────────────────────
  addLine(): void {
    this.lines.push({ tariffCode: '', description: '', quantity: 1, unitPrice: '', modifierCodes: '' });
  }

  removeLine(i: number): void {
    if (this.lines.length === 1) return;
    this.lines.splice(i, 1);
  }

  lineSubtotal(line: LineDraft): number {
    const qty = Number(line.quantity) || 0;
    const price = Number(line.unitPrice) || 0;
    return qty * price;
  }

  totalAmount(): number {
    return this.lines.reduce((sum, l) => sum + this.lineSubtotal(l), 0);
  }

  // ── Submit ───────────────────────────────────────────────────────────
  submit(): void {
    this.errorMessage = null;
    if (!this.selectedMember) { this.errorMessage = 'Pick a member'; return; }
    if (!this.selectedProvider) { this.errorMessage = 'Pick a provider'; return; }
    if (!this.form.schemeId) { this.errorMessage = 'Pick a scheme'; return; }
    if (!this.form.serviceDate) { this.errorMessage = 'Service date is required'; return; }
    const validLines = this.lines.filter(l => l.tariffCode.trim() && l.unitPrice);
    if (validLines.length === 0) { this.errorMessage = 'Add at least one line item'; return; }

    const total = this.totalAmount();
    if (total <= 0) { this.errorMessage = 'Total claimed amount must be positive'; return; }

    const payload: SubmitClaimPayload = {
      memberId: this.selectedMember.id,
      providerId: this.selectedProvider.id,
      schemeId: this.form.schemeId,
      serviceDate: this.form.serviceDate,
      claimedAmount: total.toFixed(2),
      currencyCode: this.form.currencyCode,
      diagnosisCodes: this.form.diagnosisCodes.trim() || undefined,
      notes: this.form.notes.trim() || undefined,
      lines: validLines.map(l => ({
        tariffCode: l.tariffCode.trim().toUpperCase(),
        description: l.description.trim() || undefined,
        quantity: Number(l.quantity) || 1,
        unitPrice: l.unitPrice,
        claimedAmount: this.lineSubtotal(l).toFixed(2),
        modifierCodes: l.modifierCodes.trim() || undefined,
        currencyCode: this.form.currencyCode,
      })),
    };

    this.saving = true;
    this.claims.submit(payload).subscribe({
      next: (claim) => {
        this.saving = false;
        this.submittedClaim = claim;
        this.successMessage = `Claim ${claim.claimNumber} submitted. Verification code generated — ask the member to confirm.`;
      },
      error: (err) => {
        this.saving = false;
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Submission failed';
      },
    });
  }

  // ── Verification ─────────────────────────────────────────────────────
  verify(): void {
    if (!this.submittedClaim) return;
    if (!this.verificationCode.trim()) {
      this.errorMessage = 'Enter the verification code';
      return;
    }
    this.verifying = true;
    this.errorMessage = null;
    this.claims.verify(this.submittedClaim.id, this.verificationCode.trim()).subscribe({
      next: (claim) => {
        this.verifying = false;
        this.submittedClaim = claim;
        this.successMessage = `Claim ${claim.claimNumber} verified. It's now eligible for adjudication.`;
      },
      error: (err) => {
        this.verifying = false;
        this.errorMessage = err?.error?.detail || 'Verification failed — code may be wrong or expired';
      },
    });
  }

  goToList(): void {
    this.router.navigate(['/tenant/claims']);
  }
}
