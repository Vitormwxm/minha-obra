import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, OnInit, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { AccountsApi, apiMessage } from './accounts-api';
import { AuthSession, MfaSetup } from './accounts.models';
import { AdminAccounts } from './admin-accounts';
import { HomeArt } from './home-art';
import { normalizedUsername, passwordProblem, validUsername } from './security-input';

@Component({
  selector: 'app-root',
  imports: [ReactiveFormsModule, AdminAccounts, HomeArt],
  styleUrl: './app.css',
  templateUrl: './app.html',
})
export class App implements OnInit {
  private readonly api = inject(AccountsApi);
  private readonly forms = inject(FormBuilder).nonNullable;
  readonly isAndroidApp = /MinhaObraAndroid\//.test(navigator.userAgent);
  readonly session = signal<AuthSession | null>(null);
  readonly starting = signal(true);
  readonly busy = signal(false);
  readonly adminBusy = signal(false);
  readonly message = signal('');
  readonly notice = signal('');
  readonly page = signal<'overview' | 'accounts'>('overview');
  readonly changingPassword = signal(false);
  readonly showPassword = signal(false);
  readonly recoveryMode = signal(false);
  readonly mfaSetup = signal<MfaSetup | null>(null);
  readonly recoveryCodes = signal<string[]>([]);
  readonly recoverySaved = signal(false);
  readonly copied = signal('');
  readonly loginForm = this.forms.group({
    username: ['', [Validators.required, Validators.maxLength(40)]],
    password: ['', [Validators.required, Validators.maxLength(72)]],
  });
  readonly passwordForm = this.forms.group({
    currentPassword: ['', Validators.required],
    newPassword: ['', Validators.required],
    confirmPassword: ['', Validators.required],
  });
  readonly mfaForm = this.forms.group({ code: ['', Validators.required] });

  ngOnInit(): void { void this.start(); }

  async start(): Promise<void> {
    this.starting.set(true);
    this.message.set('');
    try {
      await this.api.refreshCsrf();
      try {
        this.applySession(await this.api.me());
      } catch (error) {
        if (!(error instanceof HttpErrorResponse && error.status === 401)) throw error;
        this.session.set(null);
      }
    } catch (error) {
      this.message.set(apiMessage(error, 'O servidor não respondeu. Tente novamente em instantes.'));
    } finally {
      this.starting.set(false);
    }
  }

  async login(): Promise<void> {
    if (this.busy()) return;
    this.loginForm.markAllAsTouched();
    const values = this.loginForm.getRawValue();
    const username = normalizedUsername(values.username);
    if (this.loginForm.invalid || !validUsername(username)) {
      this.message.set('Informe seu usuário e sua senha para continuar.');
      return;
    }
    this.begin();
    try {
      this.applySession(await this.api.login(username, values.password));
      this.showPassword.set(false);
    } catch (error) {
      await this.api.recoverCsrf(error);
      this.message.set(apiMessage(error, 'Não foi possível entrar. Confira seu usuário e sua senha.'));
    } finally {
      this.loginForm.controls.password.reset('');
      this.busy.set(false);
    }
  }

  async changePassword(): Promise<void> {
    if (this.busy()) return;
    this.passwordForm.markAllAsTouched();
    const values = this.passwordForm.getRawValue();
    const problem = passwordProblem(values.newPassword);
    if (this.passwordForm.invalid || problem) {
      this.message.set(problem ?? 'Preencha as três senhas para continuar.');
      return;
    }
    if (values.newPassword !== values.confirmPassword) {
      this.message.set('A confirmação precisa ser igual à nova senha.');
      return;
    }
    if (values.currentPassword === values.newPassword) {
      this.message.set('Escolha uma senha diferente da senha atual.');
      return;
    }
    this.begin();
    try {
      this.applySession(await this.api.changePassword(values.currentPassword, values.newPassword));
      this.passwordForm.reset();
      this.changingPassword.set(false);
      this.notice.set('Sua senha foi atualizada.');
    } catch (error) {
      await this.handleAuthenticatedError(error);
    } finally {
      this.busy.set(false);
    }
  }

  async setupMfa(): Promise<void> {
    if (this.busy()) return;
    this.begin();
    try {
      this.mfaSetup.set(await this.api.setupMfa());
      this.mfaForm.reset();
      this.copied.set('');
    } catch (error) {
      await this.handleAuthenticatedError(error);
    } finally {
      this.busy.set(false);
    }
  }

  async confirmMfa(): Promise<void> {
    if (this.busy()) return;
    const code = this.mfaForm.controls.code.value.trim();
    if (!/^\d{6}$/.test(code)) {
      this.message.set('Digite os 6 números exibidos no seu autenticador.');
      return;
    }
    this.begin();
    try {
      const result = await this.api.confirmMfa(code);
      this.applySession(result.session);
      this.mfaSetup.set(null);
      this.mfaForm.reset();
      this.recoveryCodes.set(result.recoveryCodes);
      this.recoverySaved.set(false);
    } catch (error) {
      await this.handleAuthenticatedError(error);
    } finally {
      this.busy.set(false);
    }
  }

  async verifyMfa(): Promise<void> {
    if (this.busy()) return;
    const code = this.mfaForm.controls.code.value.trim();
    if (!code || (!this.recoveryMode() && !/^\d{6}$/.test(code)) || code.length > 64) {
      this.message.set(this.recoveryMode() ? 'Informe um código de recuperação.' : 'Digite os 6 números do autenticador.');
      return;
    }
    this.begin();
    try {
      this.applySession(await this.api.verifyMfa(code));
      this.mfaForm.reset();
      this.recoveryMode.set(false);
    } catch (error) {
      await this.handleAuthenticatedError(error);
    } finally {
      this.busy.set(false);
    }
  }

  async logout(): Promise<void> {
    if (this.busy() || this.adminBusy()) return;
    this.begin();
    try {
      await this.api.logout();
      this.clearSession();
      this.notice.set('Você saiu da sua conta.');
    } catch (error) {
      await this.handleAuthenticatedError(error);
    } finally {
      this.busy.set(false);
    }
  }

  sessionExpired(): void {
    this.clearSession();
    this.message.set('Sua sessão terminou. Entre novamente para continuar.');
  }

  openPasswordChange(): void {
    this.passwordForm.reset();
    this.message.set('');
    this.notice.set('');
    this.changingPassword.set(true);
    this.focusTitle();
  }

  cancelPasswordChange(): void {
    this.passwordForm.reset();
    this.message.set('');
    this.changingPassword.set(false);
    this.focusTitle();
  }

  switchRecoveryMode(): void {
    this.recoveryMode.update((value) => !value);
    this.mfaForm.reset();
    this.message.set('');
  }

  finishRecovery(): void {
    if (!this.recoverySaved()) return;
    this.recoveryCodes.set([]);
    this.recoverySaved.set(false);
    this.copied.set('');
    this.notice.set('Autenticador configurado. Seu acesso está pronto.');
    this.focusTitle();
  }

  async copy(value: string, label: string): Promise<void> {
    try {
      await navigator.clipboard.writeText(value);
      this.copied.set(label);
    } catch {
      this.message.set('Não foi possível copiar. Selecione o texto e copie manualmente.');
    }
  }

  downloadRecovery(): void {
    const username = this.session()?.user.username ?? '';
    const text = 'Minha Obra — códigos de recuperação\nUsuário: ' + username +
      '\n\nCada código pode ser usado uma única vez após sua senha.\nGuarde este arquivo em um local seguro.\n\n' +
      this.recoveryCodes().join('\n') + '\n';
    const url = URL.createObjectURL(new Blob([text], { type: 'text/plain;charset=utf-8' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = 'minha-obra-codigos-de-recuperacao.txt';
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }

  groupedSecret(): string {
    return this.mfaSetup()?.secret.replace(/(.{4})/g, '$1 ').trim() ?? '';
  }

  private begin(): void {
    this.busy.set(true);
    this.message.set('');
    this.notice.set('');
    this.copied.set('');
  }

  private applySession(session: AuthSession): void {
    this.session.set(session);
    if (session.stage !== 'MFA_SETUP') this.mfaSetup.set(null);
    this.page.set('overview');
    this.focusTitle();
  }

  private clearSession(): void {
    this.session.set(null);
    this.mfaSetup.set(null);
    this.recoveryCodes.set([]);
    this.recoverySaved.set(false);
    this.recoveryMode.set(false);
    this.changingPassword.set(false);
    this.showPassword.set(false);
    this.loginForm.reset();
    this.passwordForm.reset();
    this.mfaForm.reset();
    this.page.set('overview');
    this.copied.set('');
    this.focusTitle();
  }

  private async handleAuthenticatedError(error: unknown): Promise<void> {
    await this.api.recoverCsrf(error);
    if (error instanceof HttpErrorResponse && (error.status === 401 || error.status === 403)) {
      try {
        const session = await this.api.me();
        this.session.set(session);
      } catch (sessionError) {
        if (sessionError instanceof HttpErrorResponse && sessionError.status === 401) {
          this.sessionExpired();
          return;
        }
      }
    }
    this.message.set(apiMessage(error));
  }

  private focusTitle(): void {
    setTimeout(() => document.querySelector<HTMLElement>('main h1')?.focus(), 0);
  }
}
