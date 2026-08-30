import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { RaConfigTabComponent } from './ra-config-tab.component';
import { YieldCurvesTabComponent } from './yield-curves-tab.component';
import { ExpenseAssumptionsTabComponent } from './expense-assumptions-tab.component';
import { OpeningBalancesTabComponent } from './opening-balances-tab.component';
import { NotificationsTabComponent } from './notifications-tab.component';
import { MarketDataTabComponent } from './market-data-tab.component';

type Ifrs17SubTab =
  | 'ra'
  | 'yield-curves'
  | 'market-data'
  | 'expense-assumptions'
  | 'opening-balances'
  | 'notifications';

/**
 * IFRS 17 admin config surface — sub-tabs rendered inside a single
 * settings tab. Groups the Phase 15 §2/§7 admin surfaces the same way
 * {@link ActuarialBasesComponent} groups the Phase 14 basis tables — one
 * top-level tab, N sub-tabs — so the settings tab bar stays readable as
 * IFRS 17 grows.
 */
@Component({
  selector: 'app-ifrs17-config',
  standalone: true,
  imports: [
    CommonModule,
    IconComponent,
    RaConfigTabComponent,
    YieldCurvesTabComponent,
    ExpenseAssumptionsTabComponent,
    OpeningBalancesTabComponent,
    NotificationsTabComponent,
    MarketDataTabComponent,
  ],
  templateUrl: './ifrs17-config.component.html',
  styleUrl: '../actuarial-bases/actuarial-bases.component.scss',
})
export class Ifrs17ConfigComponent {
  activeSubTab: Ifrs17SubTab = 'ra';

  readonly subTabs: Array<{ id: Ifrs17SubTab; label: string; icon: string }> = [
    { id: 'ra',                  label: 'Risk Adjustment',    icon: 'shield' },
    { id: 'yield-curves',        label: 'Yield Curves',       icon: 'trending-up' },
    { id: 'market-data',         label: 'Market Data',        icon: 'download' },
    { id: 'expense-assumptions', label: 'Expense Assumptions', icon: 'dollar-sign' },
    { id: 'opening-balances',    label: 'Opening Balances',   icon: 'clipboard-list' },
    { id: 'notifications',       label: 'Notifications',      icon: 'bell' },
  ];
}
