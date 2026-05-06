import { Component, computed, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpLoadingService } from '../../../core/services/http-loading.service';

/**
 * Thin animated bar pinned to the top of the viewport. Visible whenever any
 * HTTP request is pending (driven by {@link HttpLoadingService}).
 *
 * Mounted once per layout — placement at the layout level keeps it visible
 * even during full-page route transitions.
 */
@Component({
  selector: 'app-progress-bar',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div
      class="progress-bar"
      [class.active]="active()"
      role="progressbar"
      aria-label="Loading"
    >
      <div class="progress-bar__inner"></div>
    </div>
  `,
  styles: [`
    .progress-bar {
      position: fixed;
      top: 0;
      left: 0;
      right: 0;
      height: 3px;
      pointer-events: none;
      z-index: 2000;
      opacity: 0;
      transition: opacity 220ms ease-out;
      overflow: hidden;
    }
    .progress-bar.active {
      opacity: 1;
    }
    .progress-bar__inner {
      width: 40%;
      height: 100%;
      background: linear-gradient(
        90deg,
        transparent 0%,
        var(--color-sidebar-active-indicator, #0077b6) 50%,
        transparent 100%
      );
      animation: progress-slide 1.2s linear infinite;
    }
    @keyframes progress-slide {
      0%   { transform: translateX(-100%); }
      100% { transform: translateX(350%); }
    }
  `],
})
export class ProgressBarComponent {
  private readonly loading = inject(HttpLoadingService);
  readonly active = computed(() => this.loading.isLoading());
}
