import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { IconComponent } from '../icon/icon.component';

/** Mime types tenancy-service accepts for a platform logo. Keep in sync with
 *  {@code PlatformSettingsService.ALLOWED_LOGO_MIMES}. */
export const LOGO_MIME_TYPES = ['image/svg+xml', 'image/png', 'image/jpeg'];

/** Server-side cap, enforced client-side too so an oversized file never
 *  makes the round trip. Keep in sync with
 *  {@code PlatformSettingsService.MAX_LOGO_BYTES}. */
export const MAX_LOGO_BYTES = 2 * 1024 * 1024;

/**
 * Drag-and-drop (or click-to-pick) logo picker. Validates mime type and size
 * before emitting, so the caller only ever sees a file the server will
 * accept; rejections surface through {@link #rejected} for a toast.
 */
@Component({
  selector: 'app-logo-uploader',
  standalone: true,
  imports: [CommonModule, IconComponent],
  templateUrl: './logo-uploader.component.html',
  styleUrl: './logo-uploader.component.scss',
})
export class LogoUploaderComponent {
  /** Preview thumbnail for the logo already on file, if any. */
  @Input() currentUrl: string | null = null;

  /** Drives the spinner + disabled state while the caller uploads. */
  @Input() uploading = false;

  @Output() fileSelected = new EventEmitter<File>();

  /** Emits a human-readable reason when a picked file fails validation. */
  @Output() rejected = new EventEmitter<string>();

  dragging = false;

  readonly accept = LOGO_MIME_TYPES.join(',');

  onDragOver(event: DragEvent): void {
    event.preventDefault();
    if (!this.uploading) this.dragging = true;
  }

  onDragLeave(): void {
    this.dragging = false;
  }

  onDrop(event: DragEvent): void {
    event.preventDefault();
    this.dragging = false;
    if (this.uploading) return;
    const file = event.dataTransfer?.files?.item(0);
    if (file) this.accepted(file);
  }

  onPick(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.item(0);
    if (file) this.accepted(file);
    // Clear so re-picking the same file still fires a change event.
    input.value = '';
  }

  private accepted(file: File): void {
    if (!LOGO_MIME_TYPES.includes(file.type)) {
      this.rejected.emit(`${file.name} is not an SVG, PNG, or JPG.`);
      return;
    }
    if (file.size > MAX_LOGO_BYTES) {
      const mb = (file.size / (1024 * 1024)).toFixed(1);
      this.rejected.emit(`${file.name} is ${mb}MB; the limit is 2MB.`);
      return;
    }
    this.fileSelected.emit(file);
  }
}
