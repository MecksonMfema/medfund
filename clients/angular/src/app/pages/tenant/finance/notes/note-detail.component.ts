import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import {
  FinanceService,
  Note,
} from '../../../../core/services/finance.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { CurrencyFormatPipe } from '../../../../shared/pipes/currency-format.pipe';
import { HumanizePipe } from '../../../../shared/pipes/humanize.pipe';

/**
 * Single-note detail + action bar. State machine surfaces:
 *   pending  → Approve  |  Delete
 *   approved → Apply    |  Delete
 *   applied  → Reverse       (compensating REVERSAL row inserted)
 *   reversed → no actions
 * A REVERSAL row shows the link back to the original it compensates.
 */
@Component({
  selector: 'app-note-detail',
  standalone: true,
  imports: [CommonModule, RouterLink, IconComponent, SkeletonComponent, CurrencyFormatPipe, HumanizePipe],
  templateUrl: './note-detail.component.html',
  styleUrl: './note-detail.component.scss',
})
export class NoteDetailComponent implements OnInit {
  note: Note | null = null;
  loading = false;
  busy = false;
  errorMessage: string | null = null;
  successMessage: string | null = null;

  constructor(
    private finance: FinanceService,
    private route: ActivatedRoute,
    private router: Router,
  ) {}

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (!id) {
      this.errorMessage = 'No note id';
      return;
    }
    this.refresh(id);
  }

  refresh(id: string): void {
    this.loading = true;
    this.finance.getNote(id).subscribe({
      next: (n) => { this.note = n; this.loading = false; },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load note';
        this.loading = false;
      },
    });
  }

  approve(): void {
    if (!this.note) return;
    this.busy = true;
    this.finance.approveNote(this.note.id).subscribe({
      next: (n) => {
        this.note = n;
        this.successMessage = `Note ${n.noteNumber} approved.`;
        this.busy = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to approve';
        this.busy = false;
      },
    });
  }

  apply(): void {
    if (!this.note) return;
    if (!confirm(`Apply note ${this.note.noteNumber}? Once applied it renders on the payee's next advice.`)) return;
    this.busy = true;
    this.finance.applyNote(this.note.id).subscribe({
      next: (n) => {
        this.note = n;
        this.successMessage = `Note ${n.noteNumber} applied.`;
        this.busy = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to apply';
        this.busy = false;
      },
    });
  }

  reverse(): void {
    if (!this.note) return;
    const reason = prompt(`Reverse note ${this.note.noteNumber}? Enter a reason (optional):`, '');
    if (reason === null) return;
    this.busy = true;
    this.finance.reverseNote(this.note.id, reason || undefined).subscribe({
      next: (reversal) => {
        this.successMessage = `Compensating REVERSAL ${reversal.noteNumber} posted.`;
        // Reload the original so the status flip to 'reversed' shows up.
        if (this.note) this.refresh(this.note.id);
        this.busy = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to reverse';
        this.busy = false;
      },
    });
  }

  deleteNote(): void {
    if (!this.note) return;
    if (!confirm(`Delete pending note ${this.note.noteNumber}? This cannot be undone.`)) return;
    this.busy = true;
    this.finance.deleteNote(this.note.id).subscribe({
      next: () => {
        this.successMessage = 'Note deleted.';
        this.busy = false;
        setTimeout(() => this.router.navigate(['/tenant/finance/notes']), 500);
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to delete';
        this.busy = false;
      },
    });
  }

  back(): void {
    this.router.navigate(['/tenant/finance/notes']);
  }
}
