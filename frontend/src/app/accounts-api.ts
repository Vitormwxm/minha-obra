import { HttpClient, HttpErrorResponse, HttpHeaders, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import {
  Account, AccountPage, AccountRequest, AccountRequestResult, ApiFailure, AuthSession, MfaConfirmation, MfaSetup,
} from './accounts.models';

@Injectable({ providedIn: 'root' })
export class AccountsApi {
  private readonly http = inject(HttpClient);
  private csrfToken: string | null = null;
  private csrfRequest: Promise<void> | null = null;

  async refreshCsrf(): Promise<void> {
    if (this.csrfRequest) return this.csrfRequest;
    this.csrfRequest = firstValueFrom(this.http.get<{ token: string; headerName: string }>('/api/auth/csrf', {
      withCredentials: true,
    })).then((csrf) => {
      if (csrf.headerName !== 'X-XSRF-TOKEN' || !csrf.token) throw new Error('Invalid CSRF response');
      this.csrfToken = csrf.token;
    }).finally(() => { this.csrfRequest = null; });
    return this.csrfRequest;
  }

  me(): Promise<AuthSession> {
    return firstValueFrom(this.http.get<AuthSession>('/api/auth/me', { withCredentials: true }));
  }

  async login(username: string, password: string): Promise<AuthSession> {
    const session = await this.post<AuthSession>('/auth/login', { username, password });
    await this.afterAuthentication();
    return session;
  }

  register(payload: AccountRequest, key: string): Promise<AccountRequestResult> {
    return this.post<AccountRequestResult>('/auth/register', payload, key);
  }

  async changePassword(currentPassword: string, newPassword: string): Promise<AuthSession> {
    const session = await this.post<AuthSession>('/auth/change-password', { currentPassword, newPassword });
    await this.afterAuthentication();
    return session;
  }

  async logout(): Promise<void> {
    try {
      await this.post<void>('/auth/logout', {});
    } catch (error) {
      if (!(error instanceof HttpErrorResponse && error.status === 401)) throw error;
    }
    await this.afterAuthentication();
  }

  setupMfa(): Promise<MfaSetup> {
    return this.post<MfaSetup>('/auth/mfa/setup', {});
  }

  async confirmMfa(code: string): Promise<MfaConfirmation> {
    const result = await this.post<MfaConfirmation>('/auth/mfa/confirm', { code });
    await this.afterAuthentication();
    return result;
  }

  async verifyMfa(code: string): Promise<AuthSession> {
    const session = await this.post<AuthSession>('/auth/mfa/verify', { code });
    await this.afterAuthentication();
    return session;
  }

  accounts(page: number, search: string): Promise<AccountPage> {
    const params = new HttpParams().set('page', page).set('size', 20).set('search', search);
    return firstValueFrom(this.http.get<AccountPage>('/api/admin/users', { params, withCredentials: true }));
  }

  approveAccount(account: Account, key: string): Promise<Account> {
    return this.post<Account>('/admin/users/' + encodeURIComponent(account.id) + '/approve',
      { version: account.version }, key);
  }

  setBlocked(account: Account, blocked: boolean, key: string): Promise<Account> {
    const action = blocked ? 'block' : 'unblock';
    return this.post<Account>('/admin/users/' + encodeURIComponent(account.id) + '/' + action,
      { version: account.version }, key);
  }

  resetPassword(account: Account, temporaryPassword: string, key: string): Promise<Account> {
    return this.post<Account>('/admin/users/' + encodeURIComponent(account.id) + '/temporary-password',
      { version: account.version, temporaryPassword }, key);
  }

  async recoverCsrf(error: unknown): Promise<void> {
    if (error instanceof HttpErrorResponse && error.status === 403) {
      this.csrfToken = null;
      await this.refreshCsrf().catch(() => undefined);
    }
  }

  async mutate<T>(method: 'POST' | 'PUT' | 'DELETE', path: string, payload: unknown, key?: string): Promise<T> {
    if (!this.csrfToken) await this.refreshCsrf();
    // Angular reads the plain XSRF cookie. A JSON CsrfToken can be XOR-masked
    // by Spring's SPA handler, so it must not replace the cookie header.
    let headers = new HttpHeaders();
    if (key) headers = headers.set('Idempotency-Key', key);
    return firstValueFrom(this.http.request<T>(method, '/api' + path, {
      body: payload, headers, withCredentials: true,
    }));
  }

  private post<T>(path: string, payload: unknown, key?: string): Promise<T> {
    return this.mutate<T>('POST', path, payload, key);
  }

  private async afterAuthentication(): Promise<void> {
    this.csrfToken = null;
    // The mutation has succeeded even if the follow-up request loses connectivity.
    // The next operation will obtain a fresh token instead of replaying the mutation.
    await this.refreshCsrf().catch(() => { this.csrfToken = null; });
  }
}

export function apiMessage(error: unknown, fallback = 'Não foi possível concluir. Tente novamente.'): string {
  if (!(error instanceof HttpErrorResponse)) return fallback;
  if (error.status === 0) return 'Sem conexão com o servidor. Confira a internet e tente novamente.';
  if (error.status === 429) {
    const seconds = Number(error.headers.get('Retry-After'));
    return Number.isFinite(seconds) && seconds > 0 && seconds <= 3600
      ? 'Muitas tentativas. Aguarde ' + Math.ceil(seconds) + ' segundos e tente novamente.'
      : 'Muitas tentativas. Aguarde um pouco e tente novamente.';
  }
  const failure = error.error as ApiFailure | null;
  if (typeof failure?.message === 'string' && failure.message.length <= 240) return failure.message;
  if (error.status === 409) return 'Esta conta foi atualizada. Atualize a lista antes de tentar novamente.';
  if (error.status === 401) return 'Sua sessão expirou. Entre novamente.';
  if (error.status === 403) return 'Não foi possível validar esta ação. Tente novamente.';
  return fallback;
}
