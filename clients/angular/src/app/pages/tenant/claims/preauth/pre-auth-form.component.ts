import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { Subject, debounceTime, distinctUntilChanged, of, switchMap } from 'rxjs';
import {
  PreAuthRequestPayload,
  PreAuthService,
} from '../../../../core/services/pre-auth.service';
import { MembersService, Member } from '../../../../core/services/members.service';
import { ProvidersService, Provider } from '../../../../core/services/providers.service';
import { ContributionsService, Scheme } from '../../../../core/services/contributions.service';
import { CurrencyService, TenantCurrencyConfig } from '../../../../core/services/currency.service';
import { TenantService } from '../../../../core/services/tenant.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';

interface PickerOption { id: string; label: string; sublabel?: string; }

@Component({
  selector: 'app-pre-auth-form',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent],
  templateUrl: './pre-auth-form.component.html',
  styleUrl: './pre-auth-form.component.scss',
})
export class PreAuthFormComponent implements OnInit {
  saving = false;
  errorMessage: string | null = null;

  memberQuery = '';
  memberMatches: PickerOption[] = [];
  selectedMember: PickerOption | null = null;
  showMemberMatches = false;
  memberSearching = false;

  providerQuery = '';
  providerMatches: PickerOption[] = [];
  selectedProvider: PickerOption | null = null;
  showProviderMatches = false;
  providerSearching = false;

  schemes: Scheme[] = [];
  currencies: TenantCurrencyConfig[] = [];

  form = {
    schemeId: '',
    tariffCode: '',
    diagnosisCode: '',
    requestedAmount: '',
    currencyCode: 'USD',
    notes: '',
  };

  private memberQuery$ = new Subject<string>();
  private providerQuery$ = new Subject<string>();

  constructor(
    private service: PreAuthService,
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
    this.contributions.getSchemes().subscribe({ next: (rows) => { this.schemes = rows; } });

    this.memberQuery$.pipe(
      debounceTime(350),
      distinctUntilChanged(),
      switchMap((q) => {
        if (!q.trim()) { this.memberSearching = false; return of<Member[]>([]); }
        this.memberSearching = true;
        return this.membersService.searchByName(q.trim());
      }),
    ).subscribe({
      next: (rows) => {
        this.memberMatches = rows.map(m => ({ id: m.id, label: `${m.firstName} ${m.lastName}`.trim(), sublabel: m.memberNumber }));
        this.memberSearching = false;
      },
      error: () => { this.memberMatches = []; this.memberSearching = false; },
    });

    this.providerQuery$.pipe(
      debounceTime(350),
      distinctUntilChanged(),
      switchMap((q) => {
        if (!q.trim()) { this.providerSearching = false; return of({ content: [] as Provider[] } as any); }
        this.providerSearching = true;
        return this.providersService.query({ q: q.trim(), size: 10 });
      }),
    ).subscribe({
      next: (page: any) => {
        const rows: Provider[] = page?.content ?? [];
        this.providerMatches = rows.map(p => ({ id: p.id, label: p.name, sublabel: p.specialty || p.registrationNumber }));
        this.providerSearching = false;
      },
      error: () => { this.providerMatches = []; this.providerSearching = false; },
    });
  }

  onMemberQueryChange(): void { this.showMemberMatches = true; this.memberQuery$.next(this.memberQuery); }
  pickMember(o: PickerOption): void { this.selectedMember = o; this.memberQuery = o.label; this.showMemberMatches = false; this.memberMatches = []; }
  clearMember(): void { this.selectedMember = null; this.memberQuery = ''; }

  onProviderQueryChange(): void { this.showProviderMatches = true; this.providerQuery$.next(this.providerQuery); }
  pickProvider(o: PickerOption): void { this.selectedProvider = o; this.providerQuery = o.label; this.showProviderMatches = false; this.providerMatches = []; }
  clearProvider(): void { this.selectedProvider = null; this.providerQuery = ''; }

  submit(): void {
    this.errorMessage = null;
    if (!this.selectedMember) { this.errorMessage = 'Pick a member'; return; }
    if (!this.selectedProvider) { this.errorMessage = 'Pick a provider'; return; }
    if (!this.form.schemeId) { this.errorMessage = 'Pick a scheme'; return; }
    if (!this.form.tariffCode.trim()) { this.errorMessage = 'Tariff code is required'; return; }
    if (!this.form.requestedAmount) { this.errorMessage = 'Requested amount is required'; return; }

    const payload: PreAuthRequestPayload = {
      memberId: this.selectedMember.id,
      providerId: this.selectedProvider.id,
      schemeId: this.form.schemeId,
      tariffCode: this.form.tariffCode.trim().toUpperCase(),
      diagnosisCode: this.form.diagnosisCode.trim() || undefined,
      requestedAmount: this.form.requestedAmount,
      currencyCode: this.form.currencyCode,
      notes: this.form.notes.trim() || undefined,
    };

    this.saving = true;
    this.service.create(payload).subscribe({
      next: (saved) => {
        this.saving = false;
        this.router.navigate(['/tenant/claims/preauth', saved.id]);
      },
      error: (err) => {
        this.saving = false;
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Save failed';
      },
    });
  }
}
