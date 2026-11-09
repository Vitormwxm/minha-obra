import { Component, inject, input } from '@angular/core';
import { NotificationDevice } from './notification-device';

@Component({
  selector: 'app-notification-device-controls',
  templateUrl: './notification-device-controls.html',
  styles: [
    '.notification-device { display: grid; gap: 10px; justify-items: start; margin: 16px 0; }',
    '.notification-device p { margin: 0; color: var(--muted); font-size: 13px; }',
  ],
})
export class NotificationDeviceControls {
  readonly admin = input(false);
  readonly device = inject(NotificationDevice);
}
