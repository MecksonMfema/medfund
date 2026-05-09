import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { Drug, DrugsService } from '../../../../core/services/drugs.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { CurrencyFormatPipe } from '../../../../shared/pipes/currency-format.pipe';
import { HumanizePipe } from '../../../../shared/pipes/humanize.pipe';

@Component({
  selector: 'app-drugs-list',
  standalone: true,
  imports: [CommonModule, RouterLink, IconComponent, SkeletonComponent, CurrencyFormatPipe, HumanizePipe],
  templateUrl: './drugs-list.component.html',
  styleUrl: './drugs-list.component.scss',
})
export class DrugsListComponent implements OnInit {
  rows: Drug[] = [];
  loading = false;
  errorMessage: string | null = null;
  pendingId: string | null = null;

  constructor(private drugs: DrugsService, private router: Router) {}

  ngOnInit(): void { this.refresh(); }

  refresh(): void {
    this.loading = true;
    this.drugs.list(false).subscribe({
      next: (rows) => { this.rows = rows; this.loading = false; },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load drug catalogue';
        this.loading = false;
      },
    });
  }

  edit(d: Drug): void { this.router.navigate(['/tenant/claims/drugs', d.id, 'edit']); }

  remove(d: Drug): void {
    if (!confirm(`Remove ${d.drugName} from the formulary?`)) return;
    this.pendingId = d.id;
    this.drugs.delete(d.id).subscribe({
      next: () => {
        this.rows = this.rows.filter(x => x.id !== d.id);
        this.pendingId = null;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Delete failed';
        this.pendingId = null;
      },
    });
  }
}
