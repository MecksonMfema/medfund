import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { IconComponent } from '../icon/icon.component';
import { ToastKind, ToastService } from './toast.service';

@Component({
  selector: 'app-toast-container',
  standalone: true,
  imports: [CommonModule, IconComponent],
  templateUrl: './toast-container.component.html',
  styleUrl: './toast-container.component.scss',
})
export class ToastContainerComponent {
  private readonly toast = inject(ToastService);
  readonly toasts$ = this.toast.toasts$;

  dismiss(id: string): void {
    this.toast.dismiss(id);
  }

  iconFor(kind: ToastKind): string {
    switch (kind) {
      case 'success': return 'check-circle';
      case 'error':   return 'alert-triangle';
      case 'warning': return 'alert-triangle';
      default:        return 'info';
    }
  }
}
