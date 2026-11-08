import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, ElementRef, inject, OnInit, output, signal } from '@angular/core';
import { AccountsApi, apiMessage } from './accounts-api';
import { DeletionApi, DeletionPage, PendingDeletion } from './deletion-api';
import { OperationKey } from './security-input';

@Component({
  selector: 'app-admin-deletions',
  imports: [DatePipe],
  templateUrl: './admin-deletions.html',
  styleUrl: './deletion.css',
})
export class AdminDeletions implements OnInit {
  private readonly api = inject(DeletionApi);
  private readonly accounts = inject(AccountsApi);
  private readonly destroy = inject(DestroyRef);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly approval = new OperationKey();

  readonly expired = output<void>();
  readonly processing = output<boolean>();
  readonly page = signal<DeletionPage | null>(null);
  readonly selected = signal<PendingDeletion | null>(null);
  readonly confirmation = signal('');
  readonly busy = signal(false);
  readonly error = signal('');
  readonly notice = signal('');

  ngOnInit(): void { void this.load(0); }

  load(page: number): Promise<void> {
    return this.run(async () => {
      this.page.set(await this.api.pending(page));
      this.selected.set(null);
      this.confirmation.set('');
    }, false);
  }

  review(request: PendingDeletion): void {
    if (this.busy()) return;
    this.selected.set(request);
    this.confirmation.set('');
    this.notice.set('');
    this.error.set('');
    setTimeout(() => this.host.nativeElement.querySelector<HTMLElement>('#approve-deletion-title')?.focus(), 0);
  }

  dismiss(): void {
    const request = this.selected();
    this.selected.set(null);
    this.confirmation.set('');
    if (request) setTimeout(() => this.host.nativeElement.querySelector<HTMLElement>(
      '#review-deletion-' + request.id,
    )?.focus(), 0);
  }

  approve(): Promise<void> {
    const request = this.selected();
    if (!request || this.confirmation() !== request.username) return Promise.resolve();
    return this.run(async () => {
      const key = await this.approval.forPayload('deletion-approve', request.id);
      await this.api.approve(request.id, key);
      this.approval.clear();
      this.dismiss();
      this.notice.set('Conta excluída da base ativa.');
      const page = this.page()?.page ?? 0;
      this.page.set(null);
      const result = await this.api.pending(page);
      this.page.set(result.items.length || page === 0 ? result : await this.api.pending(page - 1));
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
