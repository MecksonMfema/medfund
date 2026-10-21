import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { DueDateBannerRow } from '../../../../../../core/services/regulatory-due-dates.service';

/**
 * Reports-hub due-date banner (Phase 6). Rendered per report card in the
 * hub whenever a matching {@link DueDateBannerRow} exists for the row's
 * report key. All computation is server-side — the component only styles
 * the row and formats the copy.
 */
@Component({
  selector: 'app-due-date-banner',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './due-date-banner.component.html',
  styleUrl: './due-date-banner.component.scss',
})
export class DueDateBannerComponent {
  @Input({ required: true }) row!: DueDateBannerRow;

  get severityClass(): string {
    return `banner banner-${this.row.severity.toLowerCase()}`;
  }

  /** "Due in 4 days" / "Due today" / "Overdue by 3 days" / "Filed". */
  get headline(): string {
    if (this.row.submissionStatus === 'SUBMITTED' || this.row.submissionStatus === 'AMENDED') {
      return this.row.submissionStatus === 'AMENDED' ? 'Amended' : 'Filed';
    }
    const days = this.row.daysUntilDue;
    if (days > 1) return `Due in ${days} days`;
    if (days === 1) return 'Due tomorrow';
    if (days === 0) return 'Due today';
    if (days === -1) return 'Overdue by 1 day';
    return `Overdue by ${-days} days`;
  }
}
