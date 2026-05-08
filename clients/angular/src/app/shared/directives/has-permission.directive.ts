import {
  Directive,
  Input,
  OnDestroy,
  OnInit,
  TemplateRef,
  ViewContainerRef,
} from '@angular/core';
import { Subscription } from 'rxjs';
import { PermissionService } from '../../core/security/permission.service';
import { PermissionKey } from '../../core/security/permissions';

/**
 * Structural directive that renders its template only when the calling user
 * holds AT LEAST ONE of the supplied permission keys.
 *
 * <p>Examples:
 * <pre>
 *   &lt;button *hasPermission="'claims:adjudicate'"&gt;Approve&lt;/button&gt;
 *   &lt;a *hasPermission="['claims:adjudicate', 'claims:reject']"&gt;Decide&lt;/a&gt;
 * </pre>
 *
 * <p>Reactive — when {@link PermissionService#permissions$} emits a new set
 * (tenant change, refresh after role edit) the view auto-shows or auto-hides
 * without a manual reload.
 */
@Directive({
  selector: '[hasPermission]',
  standalone: true,
})
export class HasPermissionDirective implements OnInit, OnDestroy {
  private required: ReadonlyArray<PermissionKey | string> = [];
  private rendered = false;
  private sub?: Subscription;

  @Input({ required: true }) set hasPermission(value: PermissionKey | string | ReadonlyArray<PermissionKey | string>) {
    this.required = Array.isArray(value) ? value : [value as string];
    this.evaluate(this.permissions.snapshot());
  }

  constructor(
    private template: TemplateRef<unknown>,
    private vcr: ViewContainerRef,
    private permissions: PermissionService,
  ) {}

  ngOnInit(): void {
    this.sub = this.permissions.permissions$.subscribe(set => this.evaluate(set));
  }

  ngOnDestroy(): void {
    this.sub?.unsubscribe();
  }

  private evaluate(held: ReadonlySet<string>): void {
    const allow = this.required.length > 0 && this.required.some(p => held.has(p));
    if (allow && !this.rendered) {
      this.vcr.createEmbeddedView(this.template);
      this.rendered = true;
    } else if (!allow && this.rendered) {
      this.vcr.clear();
      this.rendered = false;
    }
  }
}
