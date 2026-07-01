import { Component, HostListener } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ConfirmService } from './confirm.service';

/**
 * Application-level confirmation dialog. Mount once per layout —
 * subscribes to {@link ConfirmService.current$} and renders whichever
 * ask() request is currently open. ESC + backdrop-click both cancel;
 * confirm/cancel buttons resolve the pending Promise with true/false.
 */
@Component({
  selector: 'app-confirm-dialog',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './confirm-dialog.component.html',
  styleUrl: './confirm-dialog.component.scss',
})
export class ConfirmDialogComponent {
  constructor(readonly service: ConfirmService) {}

  onCancel(): void { this.service.respond(false); }
  onConfirm(): void { this.service.respond(true); }

  /** Global ESC → cancel, matches every OS-level modal. */
  @HostListener('document:keydown.escape')
  onEscape(): void { this.service.respond(false); }
}
