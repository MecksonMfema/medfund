import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Subject, debounceTime, distinctUntilChanged, of, switchMap } from 'rxjs';
import {
  ClaimsConfigService,
  TariffCode,
} from '../../../../core/services/claims-config.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { CurrencyFormatPipe } from '../../../../shared/pipes/currency-format.pipe';

@Component({
  selector: 'app-tariff-lookup',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent, CurrencyFormatPipe],
  templateUrl: './tariff-lookup.component.html',
  styleUrl: './member-lookup.component.scss',
})
export class TariffLookupComponent {
  query = '';
  rows: TariffCode[] = [];
  searching = false;
  errorMessage: string | null = null;

  private query$ = new Subject<string>();

  constructor(private config: ClaimsConfigService) {
    this.query$.pipe(
      debounceTime(300),
      distinctUntilChanged(),
      switchMap(q => {
        if (!q.trim()) { this.searching = false; return of<TariffCode[]>([]); }
        this.searching = true;
        return this.config.searchCodes(q.trim());
      }),
    ).subscribe({
      next: (rows) => { this.rows = rows; this.searching = false; },
      error: (err) => { this.errorMessage = err?.error?.detail || 'Search failed'; this.searching = false; },
    });
  }

  onQueryChange(): void { this.query$.next(this.query); }
}
