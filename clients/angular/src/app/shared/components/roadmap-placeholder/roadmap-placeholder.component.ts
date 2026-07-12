import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { IconComponent } from '../icon/icon.component';

export interface RoadmapLink { label: string; path: string; }

export interface RoadmapConfig {
  /** Callout body under "Backend not yet available." */
  blockedBy?: string;
  /** Bullet points describing what the feature will do once shipped. */
  willDo?: string[];
  /** Where operators can find related functionality today. */
  currentAlternative?: {
    text: string;
    links?: RoadmapLink[];
  };
}

/**
 * Informative placeholder for feature routes that ship in the UI but
 * depend on a backend that isn't ready yet. Reads its content from
 * route data so multiple routes can share this component.
 *
 * Wire via:
 * <pre>
 * {
 *   path: 'special-waivers',
 *   loadComponent: () => import('.../roadmap-placeholder.component').then(m => m.RoadmapPlaceholderComponent),
 *   data: {
 *     title: 'Special waivers',
 *     description: '...',
 *     roadmap: {
 *       blockedBy: 'Requires a WaiverController on user-service.',
 *       willDo: ['Grant a member a one-off override', ...],
 *       currentAlternative: { text: 'Meanwhile, log manually via', links: [...] },
 *     },
 *   },
 * }
 * </pre>
 */
@Component({
  selector: 'app-roadmap-placeholder',
  standalone: true,
  imports: [CommonModule, RouterLink, IconComponent],
  templateUrl: './roadmap-placeholder.component.html',
  styleUrl: './roadmap-placeholder.component.scss',
})
export class RoadmapPlaceholderComponent implements OnInit {
  title = '';
  description = '';
  config: RoadmapConfig = {};

  constructor(private route: ActivatedRoute) {}

  ngOnInit(): void {
    const data = this.route.snapshot.data ?? {};
    this.title = data['title'] ?? 'Coming soon';
    this.description = data['description'] ?? '';
    this.config = data['roadmap'] ?? {};
  }
}
