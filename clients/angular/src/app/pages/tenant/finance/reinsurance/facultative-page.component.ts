import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { PermissionService } from '../../../../core/security/permission.service';
import { FacultativeCandidatesTabComponent } from './facultative-candidates-tab.component';
import { FacultativeQueueTabComponent } from './facultative-queue-tab.component';

type FacultativeTab = 'candidates' | 'queue';

/**
 * Merged Facultative reinsurance page. Two permission-gated tabs:
 * <ul>
 *   <li><b>Cedable claims</b> — underwriter surface, gated on
 *       {@code finance.reinsurance:cede_facultative}.</li>
 *   <li><b>Cession queue</b> — supervisor + viewer surface, gated on
 *       {@code finance.reinsurance:view} OR
 *       {@code finance.reinsurance:approve_facultative}. Row actions
 *       inside the tab are additionally gated on approve_facultative.</li>
 * </ul>
 * Default tab: Cession queue when the user can see it; else Cedable claims.
 */
@Component({
  selector: 'app-facultative-page',
  standalone: true,
  imports: [
    CommonModule,
    FacultativeCandidatesTabComponent,
    FacultativeQueueTabComponent,
  ],
  templateUrl: './facultative-page.component.html',
  styleUrl: './facultative-page.component.scss',
})
export class FacultativePageComponent {
  readonly showCandidatesTab: boolean;
  readonly showQueueTab: boolean;
  activeTab: FacultativeTab;

  constructor(perms: PermissionService) {
    this.showCandidatesTab = perms.hasAny(['finance.reinsurance:cede_facultative']);
    this.showQueueTab = perms.hasAny([
      'finance.reinsurance:view',
      'finance.reinsurance:approve_facultative',
    ]);
    this.activeTab = this.showQueueTab ? 'queue' : 'candidates';
  }

  setTab(tab: FacultativeTab): void {
    this.activeTab = tab;
  }
}
