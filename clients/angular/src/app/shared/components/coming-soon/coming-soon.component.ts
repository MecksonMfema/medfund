import { Component, Input } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { CommonModule } from '@angular/common';

/**
 * Placeholder rendered for any operational route that's been scaffolded but
 * not yet implemented. Two ways to use it:
 *
 * <p><b>Direct embed</b> — supply {@code title} and {@code description} as inputs:
 * <pre>
 *   &lt;app-coming-soon title="Drug Pre-Auth" description="..." /&gt;
 * </pre>
 *
 * <p><b>As a route component</b> — set {@code data: { title, description, ref }}
 * on the route and render {@code &lt;app-coming-soon /&gt;} inside the page; the
 * component reads from the route data automatically. {@code ref} surfaces the
 * legacy MASCA reference path so devs filling pages in know exactly which
 * legacy screen to translate.
 */
@Component({
  selector: 'app-coming-soon',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './coming-soon.component.html',
  styleUrl: './coming-soon.component.scss',
})
export class ComingSoonComponent {
  @Input() title?: string;
  @Input() description?: string;
  @Input() ref?: string;

  /** Falls back to the route's data object — set via `data: { title, ... }` in app.routes.ts. */
  resolvedTitle: string;
  resolvedDescription: string;
  resolvedRef: string | null;

  constructor(route: ActivatedRoute) {
    const data = route.snapshot.data ?? {};
    this.resolvedTitle = this.title ?? data['title'] ?? 'Coming soon';
    this.resolvedDescription =
      this.description ?? data['description']
        ?? "This page hasn't been implemented yet. Check back after the next release.";
    this.resolvedRef = this.ref ?? data['ref'] ?? null;
  }
}
