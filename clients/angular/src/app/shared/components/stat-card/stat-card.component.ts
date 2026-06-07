import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { IconComponent } from '../icon/icon.component';

@Component({
  selector: 'app-stat-card',
  standalone: true,
  imports: [CommonModule, RouterLink, IconComponent],
  templateUrl: './stat-card.component.html',
  styleUrl: './stat-card.component.scss',
})
export class StatCardComponent {
  @Input() label = '';
  @Input() value: string | number = '--';
  @Input() subtitle = '';
  @Input() color = 'blue';
  @Input() icon = 'chart';
  @Input() trend: number | null = null;
  @Input() actionLabel = '';
  @Input() actionLink: string | null = null;
}
