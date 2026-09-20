import {
  Directive,
  Input,
  OnDestroy,
  OnInit,
  TemplateRef,
  ViewContainerRef,
} from '@angular/core';
import { Subscription } from 'rxjs';
import { FeatureFlagService, PlatformFlagKey } from '../../core/services/feature-flag.service';

/**
 * Structural directive that renders its template only when the named
 * platform feature flag is enabled.
 *
 * <p>Example:
 * <pre>
 *   &lt;div *featureGate="'AI_ADJUDICATION'"&gt;AI score&lt;/div&gt;
 * </pre>
 *
 * <p>Reactive — re-evaluates whenever {@link FeatureFlagService#flags$}
 * emits, so a refreshed catalogue shows or hides the view without a reload.
 * An unfetched or unknown key reads as disabled, which is the safe default
 * for a feature that has not launched.
 */
@Directive({
  selector: '[featureGate]',
  standalone: true,
})
export class FeatureGateDirective implements OnInit, OnDestroy {
  private key: PlatformFlagKey | null = null;
  private rendered = false;
  private sub?: Subscription;

  @Input({ required: true }) set featureGate(value: PlatformFlagKey) {
    this.key = value;
    this.evaluate();
  }

  constructor(
    private template: TemplateRef<unknown>,
    private vcr: ViewContainerRef,
    private flags: FeatureFlagService,
  ) {}

  ngOnInit(): void {
    this.sub = this.flags.flags$.subscribe(() => this.evaluate());
  }

  ngOnDestroy(): void {
    this.sub?.unsubscribe();
  }

  private evaluate(): void {
    const allow = this.key !== null && this.flags.isEnabled(this.key);
    if (allow && !this.rendered) {
      this.vcr.createEmbeddedView(this.template);
      this.rendered = true;
    } else if (!allow && this.rendered) {
      this.vcr.clear();
      this.rendered = false;
    }
  }
}
