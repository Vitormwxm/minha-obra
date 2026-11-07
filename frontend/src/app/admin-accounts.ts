import { HttpErrorResponse } from '@angular/common/http';
import { Component, ElementRef, inject, input, OnDestroy, OnInit, output, signal, viewChild } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { AccountsApi, apiMessage } from './accounts-api';
import { Account, AccountPage } from './accounts.models';
import { newTemporaryPassword, OperationKey, passwordProblem } from './security-input';

type AccountAction = 'approve' | 'reset' | 'block' | 'unblock';
type AccountDialog = { kind: AccountAction; account: Account };

@Component({
  selector: 'app-admin-accounts',
  imports: [ReactiveFormsModule],
  templateUrl: './admin-accounts.html',
  styleUrl: './admin-accounts.css',
})
export class AdminAccounts implements OnInit, OnDestroy {
  private readonly api = inject(AccountsApi);
  private readonly forms = inject(FormBuilder).nonNullable;
  private readonly operationKey = new OperationKey();
  private requestNumber = 0;
  private destroyed = false;
  private returnFocus: HTMLElement | null = null;
  readonly currentUser = input.required<Account>();
  readonly expired = output<void>();
  readonly processing = output<boolean>();
  readonly dialogElement = viewChild<ElementRef<HTMLDialogElement>>('accountDialog');
  readonly result = signal<AccountPage>({ items: [], page: 0, size: 20, total: 0 });
  readonly loading = signal(true);
  readonly busy = signal(false);
  readonly message = signal('');
  readonly notice = signal('');
  readonly dialogMessage = signal('');
  readonly dialog = signal<AccountDialog | null>(null);
  readonly showTemporaryPassword = signal(false);
  readonly passwordCopied = signal(false);
  readonly searchForm = this.forms.group({ search: ['', Validators.maxLength(120)] });
  readonly accountForm = this.forms.group({
    temporaryPassword: ['', Validators.required],
  });

  ngOnInit(): void { void this.load(0); }

  ngOnDestroy(): void {
    this.destroyed = true;
    this.accountForm.reset();
    this.operationKey.clear();
    this.dialogElement()?.nativeElement.close();
    this.processing.emit(false);
  }

  async load(page = this.result().page): Promise<void> {
    const request = ++this.requestNumber;
    this.loading.set(true);
    this.message.set('');
    try {
      const result = await this.api.accounts(page, this.searchForm.controls.search.value.trim());
      if (request !== this.requestNumber || this.destroyed) return;
      if (result.items.length === 0 && result.total > 0 && page > 0) {
        await this.load(Math.max(0, Math.ceil(result.total / result.size) - 1));
        return;
      }
      this.result.set(result);
      const state = this.dialog();
      if (state) {
        const current = result.items.find((account) => account.id === state.account.id);
        if (current) this.dialog.set({ ...state, account: current });
      }
    } catch (error) {
      if (request !== this.requestNumber || this.destroyed) return;
      if (error instanceof HttpErrorResponse && error.status === 401) this.expired.emit();
      else this.message.set(apiMessage(error, 'Não foi possível carregar as contas.'));
    } finally {
      if (request === this.requestNumber && !this.destroyed) this.loading.set(false);
    }
  }

  search(): void {
    if (this.searchForm.invalid || this.loading() || this.busy()) return;
    void this.load(0);
  }

  openAction(kind: AccountAction, account: Account): void {
    if (!this.canAct(kind, account)) return;
    this.open({ kind, account });
  }

  closeDialog(): void { this.close(false); }

  cancelDialog(event: Event): void {
    event.preventDefault();
    this.close(false);
  }

  generatePassword(): void {
    this.accountForm.controls.temporaryPassword.setValue(newTemporaryPassword());
    this.passwordCopied.set(false);
  }

  async copyPassword(): Promise<void> {
    const password = this.accountForm.controls.temporaryPassword.value;
    if (!password) {
      this.dialogMessage.set('Defina ou gere uma senha provisória primeiro.');
      return;
    }
    try {
      await navigator.clipboard.writeText(password);
      this.passwordCopied.set(true);
    } catch {
      this.showTemporaryPassword.set(true);
      setTimeout(() => {
        const input = document.getElementById('temporary-password') as HTMLInputElement | null;
        input?.focus();
        input?.select();
      }, 0);
      this.dialogMessage.set('Selecione a senha e copie manualmente.');
    }
  }

  async submit(): Promise<void> {
    const state = this.dialog();
    if (!state || this.busy() || this.loading()) return;
    if (!this.canAct(state.kind, state.account)) {
      this.dialogMessage.set('A situação desta conta mudou. Confira a lista de contas.');
      return;
    }
    const { temporaryPassword } = this.accountForm.getRawValue();
    this.accountForm.markAllAsTouched();
    if (state.kind === 'reset') {
      const problem = passwordProblem(temporaryPassword);
      if (problem) {
        this.dialogMessage.set(problem);
        return;
      }
    }
    this.busy.set(true);
    this.processing.emit(true);
    this.dialogMessage.set('');
    this.notice.set('');
    try {
      const scope = state.kind + ':' + state.account.id;
      const version = state.account.version;
      if (state.kind === 'reset') {
        const key = await this.operationKey.forPayload(scope, { version, temporaryPassword });
        await this.api.resetPassword(state.account, temporaryPassword, key);
        this.notice.set('Senha provisória definida. Entregue a nova senha à pessoa. Os acessos anteriores foram encerrados.');
      } else {
        const key = await this.operationKey.forPayload(scope, { version });
        if (state.kind === 'approve') {
          await this.api.approveAccount(state.account, key);
          this.notice.set('Conta aprovada. A pessoa já pode entrar com a senha que escolheu.');
        } else {
          await this.api.setBlocked(state.account, state.kind === 'block', key);
          this.notice.set(state.kind === 'block' ? 'Conta bloqueada. Os acessos anteriores foram encerrados.' : 'Conta desbloqueada.');
        }
      }
      this.close(true);
      await this.load();
    } catch (error) {
      await this.api.recoverCsrf(error);
      if (error instanceof HttpErrorResponse && error.status === 401) {
        this.close(true);
        this.expired.emit();
      } else {
        this.dialogMessage.set(apiMessage(error));
        if (error instanceof HttpErrorResponse && error.status === 409) await this.load();
      }
    } finally {
      this.busy.set(false);
      this.processing.emit(false);
    }
  }

  firstItem(): number {
    return this.result().total ? this.result().page * this.result().size + 1 : 0;
  }

  lastItem(): number {
    return Math.min((this.result().page + 1) * this.result().size, this.result().total);
  }

  createdOn(value: string): string {
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? '—' : new Intl.DateTimeFormat('pt-BR', {
      day: '2-digit', month: 'short', year: 'numeric',
    }).format(date);
  }

  private canAct(kind: AccountAction, account: Account): boolean {
    if (account.id === this.currentUser().id || account.role === 'ADMIN') return false;
    if (kind === 'approve') return account.status === 'PENDING_APPROVAL';
    if (kind === 'block') return account.status === 'ACTIVE';
    if (kind === 'unblock') return account.status === 'BLOCKED';
    return account.status === 'ACTIVE' || account.status === 'BLOCKED';
  }

  private open(state: AccountDialog): void {
    if (this.busy() || this.loading()) return;
    this.returnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    this.accountForm.reset();
    this.operationKey.clear();
    this.dialogMessage.set('');
    this.showTemporaryPassword.set(false);
    this.passwordCopied.set(false);
    this.dialog.set(state);
    setTimeout(() => {
      const dialog = this.dialogElement()?.nativeElement;
      if (!dialog || this.destroyed) return;
      dialog.showModal();
      const id = state.kind === 'reset' ? 'temporary-password' : 'dialog-cancel';
      document.getElementById(id)?.focus();
    }, 0);
  }

  private close(force: boolean): void {
    if (this.busy() && !force) return;
    this.dialogElement()?.nativeElement.close();
    this.accountForm.reset();
    this.operationKey.clear();
    this.dialog.set(null);
    this.showTemporaryPassword.set(false);
    this.passwordCopied.set(false);
    this.dialogMessage.set('');
    if (this.returnFocus?.isConnected) this.returnFocus.focus();
  }
}
