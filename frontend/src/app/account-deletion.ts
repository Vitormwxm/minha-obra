import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, ElementRef, inject, input, OnInit, output, signal } from '@angular/core';
import { AccountsApi, apiMessage } from './accounts-api';
import { DeletionApi, DeletionState } from './deletion-api';
import { FinanceApi } from './finance-api';
import { downloadFinanceCsv } from './finance-csv';
import { OperationKey } from './security-input';

@Component({
  selector: 'app-account-deletion',
  imports: [DatePipe],
  templateUrl: './account-deletion.html',
  styleUrl: './deletion.css',
})
export class AccountDeletion implements OnInit {
  private readonly api = inject(DeletionApi);
  private readonly accounts = inject(AccountsApi);
  private readonly finance = inject(FinanceApi);
  private readonly destroy = inject(DestroyRef);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly operation = new OperationKey();

  readonly webDownloads = input.required<boolean>();
  readonly expired = output<void>();
  readonly processing = output<boolean>();
  readonly state = signal<DeletionState | null>(null);
  readonly busy = signal(false);
  readonly confirming = signal(false);
  readonly error = signal('');
  readonly notice = signal('');

  ngOnInit(): void { void this.load(); }

  load(): Promise<void> {
    return this.run(async () => this.state.set(await this.api.state()), false);
  }

  confirm(value: boolean): void {
    this.confirming.set(value);
    setTimeout(() => this.host.nativeElement.querySelector<HTMLElement>(
      value ? '#confirm-deletion-title' : '#start-deletion',
    )?.focus(), 0);
  }

  request(): Promise<void> {
    if (!this.confirming() || !this.state() || this.state()?.request) return Promise.resolve();
    return this.run(async () => {
      const key = await this.operation.forPayload('deletion-request', null);
      this.state.set(await this.api.request(key));
      this.operation.clear();
      this.confirming.set(false);
      this.notice.set('Solicitação de exclusão enviada.');
    });
  }

  cancel(): Promise<void> {
    const request = this.state()?.request;
    if (!request) return Promise.resolve();
    return this.run(async () => {
      const key = await this.operation.forPayload('deletion-cancel', request.id);
      this.state.set(await this.api.cancel(request.id, key));
      this.operation.clear();
      this.notice.set('Solicitação cancelada. Você pode voltar a registrar pagamentos.');
    });
  }

  download(kind: 'summary' | 'payments'): Promise<void> {
    if (!this.webDownloads()) return Promise.resolve();
    return this.run(async () => {
      const ledger = await this.finance.get();
      if (!ledger.months.length || (kind === 'payments' && !ledger.months.some((row) => row.payments.length))) {
        this.notice.set('Ainda não há dados para este arquivo.');
        return;
      }
      downloadFinanceCsv(ledger, kind);
      this.notice.set('Arquivo CSV preparado para download.');
    });
  }

  private async run(action: () => Promise<void>, lockWorkspace = true): Promise<void> {
    if (this.busy()) return;
    this.busy.set(true);
    if (lockWorkspace) this.processing.emit(true);
    this.error.set('');
    this.notice.set('');
    try {
      await action();
    } catch (error) {
      if (this.destroy.destroyed) return;
      if (error instanceof HttpErrorResponse && error.status === 401) {
        this.expired.emit();
      } else {
        await this.accounts.recoverCsrf(error);
        this.error.set(apiMessage(error));
      }
    } finally {
      this.busy.set(false);
      if (lockWorkspace && !this.destroy.destroyed) this.processing.emit(false);
    }
  }
}
