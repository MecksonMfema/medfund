import { CommonModule } from '@angular/common';
import { Component, EventEmitter, Input, Output, inject } from '@angular/core';
import {
  PolicyAction,
  PolicyLifecycleActionRegistryService,
  PolicySource,
} from '../../../core/services/policy-lifecycle-action-registry.service';

/**
 * Phase 13 §A per L4 + L5 (grill note 7). Renders the 4-button action row
 * filtered by current status. Purely presentational — parent owns the modal.
 */
@Component({
  selector: 'app-policy-status-action-buttons',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './policy-status-action-buttons.component.html',
  styleUrl: './policy-status-action-buttons.component.scss',
})
export class PolicyStatusActionButtonsComponent {
  @Input({ required: true }) currentStatus!: string;
  @Input({ required: true }) policySource!: PolicySource;
  @Output() actionClicked = new EventEmitter<PolicyAction>();

  registry = inject(PolicyLifecycleActionRegistryService);

  get availableActions(): PolicyAction[] {
    return this.registry.actionsFor(this.currentStatus);
  }

  label(action: PolicyAction): string {
    return this.registry.actionLabel(action);
  }

  buttonClass(action: PolicyAction): string {
    switch (action) {
      case 'terminate': return 'btn small danger';
      case 'lapse':     return 'btn small warn';
      case 'suspend':   return 'btn small warn';
      case 'reinstate': return 'btn small success';
      default:          return 'btn small';
    }
  }
}
