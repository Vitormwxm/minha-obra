import { HttpErrorResponse } from '@angular/common/http';
import { Component, ElementRef, inject, input, OnDestroy, OnInit, output, signal, viewChild } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { AccountsApi, apiMessage } from './accounts-api';
import { Account, AccountPage } from './accounts.models';
import { newTemporaryPassword, normalizedUsername, OperationKey, passwordProblem, validUsername } from './security-input';

type AccountDialog = { kind: 'create' } | { kind: 'reset' | 'block' | 'unblock'; account: Account };

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
    username: ['', [Validators.required, Validators.maxLength(40)]],
    displayName: ['', [Validators.required, Validators.minLength(2), Validators.maxLength(120)]],
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
      if (state && state.kind !== 'create') {
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

  openCreate(): void { this.open({ kind: 'create' }); }

  openAction(kind: 'reset' | 'block' | 'unblock', account: Account): void {
    if (account.id === this.currentUser().id || account.role === 'ADMIN') return;
    this.open({ kind, account });
  }

  target(): Account | null {
    const state = this.dialog();
    return state && state.kind !== 'create' ? state.account : null;
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
    if (!state || this.busy()) return;
    const values = this.accountForm.getRawValue();
    this.accountForm.markAllAsTouched();
    if (state.kind === 'create') {
      if (!validUsername(normalizedUsername(values.username))) {
        this.dialogMessage.set('Use de 3 a 40 letras minúsculas, números, pontos, hífens ou sublinhados. Comece com letra ou número.');
        return;
      }
      if (values.displayName.trim().length < 2 || values.displayName.trim().length > 120) {
        this.dialogMessage.set('Informe um nome de 2 a 120 caracteres.');
        return;
      }
    }
    if (state.kind === 'create' || state.kind === 'reset') {
      const problem = passwordProblem(values.temporaryPassword);
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
      if (state.kind === 'create') {
        const payload = {
          username: normalizedUsername(values.username),
          displayName: values.displayName.trim(),
          temporaryPassword: values.temporaryPassword,
        };
        const key = await this.operationKey.forPayload('create-account', payload);
        await this.api.createAccount(payload, key);
        this.notice.set('Conta criada. Entregue os dados de acesso e a senha provisória que você definiu.');
      } else if (state.kind === 'reset') {
        const key = await this.operationKey.forPayload('reset-password:' + state.account.id,
          { version: state.account.version, temporaryPassword: values.temporaryPassword });
        await this.api.resetPassword(state.account, values.temporaryPassword, key);
        this.notice.set('Senha provisória redefinida. Os acessos anteriores foram encerrados; entregue a nova senha à pessoa.');
      } else {
        const key = await this.operationKey.forPayload(state.kind + ':' + state.account.id, { version: state.account.version });
        await this.api.setBlocked(state.account, state.kind === 'block', key);
        this.notice.set(state.kind === 'block' ? 'Conta bloqueada. Os acessos anteriores foram encerrados.' : 'Conta desbloqueada.');
      }
      this.operationKey.clear();
      this.close(true);
      await this.load(state.kind === 'create' ? 0 : this.result().page);
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
      const id = state.kind === 'create' ? 'account-username' :
        state.kind === 'reset' ? 'temporary-password' : 'dialog-cancel';
      document.getElementById(id)?.focus();
    }, 0);
  }

  private close(force: boolean): void {
    if (this.busy() && !force) return;
    this.dialogElement()?.nativeElement.close();
    this.accountForm.reset();
    this.dialog.set(null);
    this.showTemporaryPassword.set(false);
    this.passwordCopied.set(false);
    this.dialogMessage.set('');
    if (this.returnFocus?.isConnected) this.returnFocus.focus();
  }
}
