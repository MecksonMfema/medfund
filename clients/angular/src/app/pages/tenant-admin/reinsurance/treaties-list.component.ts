import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import {
  ReinsuranceService,
  Treaty,
  TreatyStatus,
} from '../../../core/services/reinsurance.service';
import { IconComponent } from '../../../shared/components/icon/icon.component';

interface TreatyChain {
  rootRef: string;
  treaties: Treaty[];
}

@Component({
  selector: 'app-treaties-list',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent],
  templateUrl: './treaties-list.component.html',
  styleUrl: './treaties-list.component.scss',
})
export class TreatiesListComponent implements OnInit {
  chains: TreatyChain[] = [];
  loading = false;
  errorMessage: string | null = null;
  filterStatus: '' | TreatyStatus = '';

  constructor(private svc: ReinsuranceService) {}

  ngOnInit(): void { this.fetchAll(); }

  fetchAll(): void {
    this.loading = true;
    this.svc.listTreaties(0, 500, this.filterStatus || undefined).subscribe({
      next: (resp) => {
        this.chains = this.groupByChain(resp.content);
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Failed to load treaties';
        this.chains = [];
        this.loading = false;
      },
    });
  }

  /**
   * Group treaties by renewal chain: a treaty with no {@code renewedFromTreatyId}
   * is a root; any successor is filed under the root whose renewal chain leads
   * to it. Simple two-pass — enough for typical treaty counts.
   */
  private groupByChain(all: Treaty[]): TreatyChain[] {
    const byId = new Map(all.map(t => [t.id, t] as const));
    const rootFor = (t: Treaty): Treaty => {
      let cur = t;
      const seen = new Set<string>();
      while (cur.renewedFromTreatyId && byId.has(cur.renewedFromTreatyId) && !seen.has(cur.id)) {
        seen.add(cur.id);
        cur = byId.get(cur.renewedFromTreatyId)!;
      }
      return cur;
    };

    const grouped = new Map<string, Treaty[]>();
    for (const t of all) {
      const root = rootFor(t);
      const arr = grouped.get(root.id) ?? [];
      arr.push(t);
      grouped.set(root.id, arr);
    }
    return Array.from(grouped.entries())
      .map(([, treaties]) => {
        // Ancestors first: order by inception date ascending.
        treaties.sort((a, b) => a.inceptionDate.localeCompare(b.inceptionDate));
        return { rootRef: treaties[0].treatyRef, treaties };
      })
      .sort((a, b) => a.rootRef.localeCompare(b.rootRef));
  }

  statusBadgeClass(status: TreatyStatus): string {
    switch (status) {
      case 'DRAFT':    return 'badge badge-neutral';
      case 'ACTIVE':   return 'badge badge-success';
      case 'RENEWED':  return 'badge badge-info';
      case 'EXPIRED':  return 'badge badge-warning';
      case 'LAPSED':
      case 'COMMUTED': return 'badge badge-danger';
    }
  }
}
