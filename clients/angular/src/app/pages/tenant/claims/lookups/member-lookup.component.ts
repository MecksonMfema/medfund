import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { Subject, debounceTime, distinctUntilChanged, of, switchMap } from 'rxjs';
import { MembersService, Member } from '../../../../core/services/members.service';
import { ClaimsService, Claim } from '../../../../core/services/claims.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { CurrencyFormatPipe } from '../../../../shared/pipes/currency-format.pipe';
import { HumanizePipe } from '../../../../shared/pipes/humanize.pipe';
import { ToastService } from '../../../../shared/components/toast/toast.service';
import { extractErrorMessage } from '../../../../core/util/http-errors';

@Component({
  selector: 'app-member-lookup',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent, CurrencyFormatPipe, HumanizePipe],
  templateUrl: './member-lookup.component.html',
  styleUrl: './member-lookup.component.scss',
})
export class MemberLookupComponent {
  query = '';
  matches: Member[] = [];
  searching = false;
  selected: Member | null = null;
  history: Claim[] = [];
  loadingHistory = false;

  private query$ = new Subject<string>();

  constructor(
    private members: MembersService,
    private claims: ClaimsService,
    private router: Router,
    private toast: ToastService,
  ) {
    this.query$.pipe(
      debounceTime(300),
      distinctUntilChanged(),
      switchMap(q => {
        if (!q.trim()) { this.searching = false; return of<Member[]>([]); }
        this.searching = true;
        return this.members.searchByName(q.trim());
      }),
    ).subscribe({
      next: (rows) => { this.matches = rows; this.searching = false; },
      error: (err) => { this.toast.error(extractErrorMessage(err, 'Search failed')); this.searching = false; },
    });
  }

  onQueryChange(): void { this.query$.next(this.query); }

  pick(m: Member): void {
    this.selected = m;
    this.matches = [];
    this.query = `${m.firstName} ${m.lastName}`.trim();
    this.loadingHistory = true;
    this.claims.getByMember(m.id).subscribe({
      next: (rows) => { this.history = rows; this.loadingHistory = false; },
      error: (err) => { this.toast.error(extractErrorMessage(err, 'Failed to load claims')); this.loadingHistory = false; },
    });
  }

  clear(): void { this.selected = null; this.history = []; this.query = ''; }

  openClaim(c: Claim): void { this.router.navigate(['/tenant/claims', c.id]); }
}
