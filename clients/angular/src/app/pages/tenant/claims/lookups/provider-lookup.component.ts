import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { Subject, debounceTime, distinctUntilChanged, of, switchMap } from 'rxjs';
import { ProvidersService, Provider } from '../../../../core/services/providers.service';
import { ClaimsService, Claim } from '../../../../core/services/claims.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { CurrencyFormatPipe } from '../../../../shared/pipes/currency-format.pipe';
import { HumanizePipe } from '../../../../shared/pipes/humanize.pipe';

@Component({
  selector: 'app-provider-lookup',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent, CurrencyFormatPipe, HumanizePipe],
  templateUrl: './provider-lookup.component.html',
  styleUrl: './member-lookup.component.scss',
})
export class ProviderLookupComponent {
  query = '';
  matches: Provider[] = [];
  searching = false;
  selected: Provider | null = null;
  history: Claim[] = [];
  loadingHistory = false;
  errorMessage: string | null = null;

  private query$ = new Subject<string>();

  constructor(
    private providers: ProvidersService,
    private claims: ClaimsService,
    private router: Router,
  ) {
    this.query$.pipe(
      debounceTime(300),
      distinctUntilChanged(),
      switchMap(q => {
        if (!q.trim()) { this.searching = false; return of({ content: [] as Provider[] } as any); }
        this.searching = true;
        return this.providers.query({ q: q.trim(), size: 20 });
      }),
    ).subscribe({
      next: (page: any) => { this.matches = page?.content ?? []; this.searching = false; },
      error: (err) => { this.errorMessage = err?.error?.detail || 'Search failed'; this.searching = false; },
    });
  }

  onQueryChange(): void { this.query$.next(this.query); }

  pick(p: Provider): void {
    this.selected = p;
    this.matches = [];
    this.query = p.name;
    this.loadingHistory = true;
    this.claims.getByProvider(p.id).subscribe({
      next: (rows) => { this.history = rows; this.loadingHistory = false; },
      error: (err) => { this.errorMessage = err?.error?.detail || 'Failed to load claims'; this.loadingHistory = false; },
    });
  }

  clear(): void { this.selected = null; this.history = []; this.query = ''; }

  openClaim(c: Claim): void { this.router.navigate(['/tenant/claims', c.id]); }
}
