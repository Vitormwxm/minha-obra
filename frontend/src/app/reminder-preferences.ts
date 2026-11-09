import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, inject, OnInit, output, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { AccountsApi, apiMessage } from './accounts-api';
import { OperationKey } from './security-input';

interface Preferences {
  version: number;
  enabled: boolean;
  offsets: number[];
  time: string;
  afterDue: boolean;
  weekly: boolean;
  showAmounts: boolean;
  caixaDay: number | null;
  builderDay: number | null;
}

@Component({
  selector: 'app-reminder-preferences',
  imports: [ReactiveFormsModule],
  templateUrl: './reminder-preferences.html',
  styleUrl: './deletion.css',
})
export class ReminderPreferences implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly accounts = inject(AccountsApi);
  private readonly forms = inject(FormBuilder).nonNullable;
  private readonly destroy = inject(DestroyRef);
  private readonly key = new OperationKey();

  readonly expired = output<void>();
  readonly processing = output<boolean>();
  readonly current = signal<Preferences | null>(null);
  readonly busy = signal(false);
  readonly loading = signal(false);
  readonly error = signal('');
  readonly notice = signal('');
  readonly form = this.forms.group({
    enabled: false, offsets: '', time: '09:00', afterDue: true, weekly: false,
    showAmounts: true, caixaDay: '', builderDay: '',
  });

  ngOnInit(): void { void this.load(); }

  async load(): Promise<void> {
    if (this.loading() || this.busy()) return;
    this.loading.set(true);
    this.error.set('');
    try {
      const prefs = await firstValueFrom(this.http.get<Preferences>('/api/notifications/preferences', {
        withCredentials: true,
      }));
      this.apply(prefs);
    } catch (error) {
      await this.handleError(error);
    } finally {
      this.loading.set(false);
    }
  }

  async save(): Promise<void> {
    const current = this.current();
    if (!current || this.busy() || this.loading()) return;
    this.error.set('');
    this.notice.set('');
    const values = this.form.getRawValue();
    const rawOffsets = values.offsets.trim() ? values.offsets.split(',').map((value) => value.trim()) : [];
    const offsets = rawOffsets.map(Number);
    if (rawOffsets.length > 8 || rawOffsets.some((value) => !/^\d{1,2}$/.test(value))
        || offsets.some((value) => value > 30) || new Set(offsets).size !== offsets.length) {
      this.error.set('Use até oito dias distintos de antecedência, de 0 a 30, separados por vírgulas.');
      return;
    }
    if (!/^(?:[01]\d|2[0-3]):[0-5]\d$/.test(values.time)) {
      this.error.set('Informe o horário dos lembretes.');
      return;
    }
    if ([values.caixaDay, values.builderDay].some((value) =>
      value.trim() && (!/^\d{1,2}$/.test(value.trim()) || Number(value) < 1 || Number(value) > 31))) {
      this.error.set('Informe um dia de 1 a 31 para cada vencimento recorrente.');
      return;
    }
    const payload: Preferences = {
      ...values, version: current.version, offsets: offsets.sort((a, b) => b - a),
      caixaDay: values.caixaDay.trim() ? Number(values.caixaDay) : null,
      builderDay: values.builderDay.trim() ? Number(values.builderDay) : null,
    };
    this.busy.set(true);
    this.processing.emit(true);
    try {
      const key = await this.key.forPayload('reminder-preferences', payload);
      this.apply(await this.accounts.mutate<Preferences>('PUT', '/notifications/preferences', payload, key));
      this.key.clear();
      this.notice.set('Preferências salvas.');
    } catch (error) {
      await this.handleError(error);
    } finally {
      this.busy.set(false);
      if (!this.destroy.destroyed) this.processing.emit(false);
    }
  }

  private apply(prefs: Preferences): void {
    this.current.set(prefs);
    this.form.reset({
      ...prefs, offsets: prefs.offsets.join(', '),
      caixaDay: prefs.caixaDay?.toString() ?? '', builderDay: prefs.builderDay?.toString() ?? '',
    });
  }

  private async handleError(error: unknown): Promise<void> {
    if (this.destroy.destroyed) return;
    if (error instanceof HttpErrorResponse && error.status === 401) {
      this.expired.emit();
      return;
    }
    await this.accounts.recoverCsrf(error);
    this.error.set(apiMessage(error, 'Não foi possível salvar ou consultar os lembretes.'));
  }
}
