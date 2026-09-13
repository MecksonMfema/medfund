import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subject, debounceTime, distinctUntilChanged, of, switchMap } from 'rxjs';
import {
  ClaimsConfigService,
  IcdCode,
} from '../../../../core/services/claims-config.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { ToastService } from '../../../../shared/components/toast/toast.service';
import { extractErrorMessage } from '../../../../core/util/http-errors';

@Component({
  selector: 'app-icd-codes-search',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent],
  templateUrl: './icd-codes-search.component.html',
  styleUrl: './icd-codes-search.component.scss',
})
export class IcdCodesSearchComponent {
  query = '';
  rows: IcdCode[] = [];
  searching = false;

  private query$ = new Subject<string>();

  constructor(private config: ClaimsConfigService, private toast: ToastService) {
    this.query$
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        switchMap((q) => {
          if (!q.trim()) {
            this.searching = false;
            return of<IcdCode[]>([]);
          }
          this.searching = true;
          return this.config.searchIcdCodes(q.trim());
        }),
      )
      .subscribe({
        next: (rows) => { this.rows = rows; this.searching = false; },
        error: (err) => {
          this.toast.error(extractErrorMessage(err, 'Search failed'));
          this.searching = false;
        },
      });
  }

  onQueryChange(): void {
    this.query$.next(this.query);
  }
}
