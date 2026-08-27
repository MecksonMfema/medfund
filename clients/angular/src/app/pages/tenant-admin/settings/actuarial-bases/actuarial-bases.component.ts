import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { PersistencyBasisTabComponent } from './persistency-basis-tab.component';
import { MortalityBasisTabComponent } from './mortality-basis-tab.component';
import { MorbidityBasisTabComponent } from './morbidity-basis-tab.component';

type ActuarialSubTab = 'persistency' | 'mortality' | 'morbidity';

/**
 * Actuarial-basis admin surface — three sub-tabs (persistency, mortality,
 * morbidity) rendered inside a single settings tab. Each sub-tab wraps a CRUD
 * table backed by its own tenancy-service endpoint. Consumed by the
 * <code>PERSISTENCY_STUDY</code>, <code>MORTALITY_STUDY</code>, and
 * <code>MORBIDITY_STUDY</code> reports.
 */
@Component({
  selector: 'app-actuarial-bases',
  standalone: true,
  imports: [
    CommonModule,
    IconComponent,
    PersistencyBasisTabComponent,
    MortalityBasisTabComponent,
    MorbidityBasisTabComponent,
  ],
  templateUrl: './actuarial-bases.component.html',
  styleUrl: './actuarial-bases.component.scss',
})
export class ActuarialBasesComponent {
  activeSubTab: ActuarialSubTab = 'persistency';

  readonly subTabs: Array<{ id: ActuarialSubTab; label: string; icon: string }> = [
    { id: 'persistency', label: 'Persistency', icon: 'trending-up' },
    { id: 'mortality',   label: 'Mortality',   icon: 'heart' },
    { id: 'morbidity',   label: 'Morbidity',   icon: 'activity' },
  ];
}
