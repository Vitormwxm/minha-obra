import { inject, Injectable, signal } from '@angular/core';
import { AccountsApi, apiMessage } from './accounts-api';
import { AuthSession } from './accounts.models';

interface NativeNotifications {
  postMessage(message: string): void;
  onmessage: ((event: MessageEvent<string>) => void) | null;
}

interface DeviceReply {
  id: number;
  status: 'inactive' | 'active' | 'ready' | 'permission' | 'unavailable';
  installationId: string;
  token?: string;
  timeZone?: string;
}

const uuid = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/;

@Injectable({ providedIn: 'root' })
export class NotificationDevice {
  private readonly api = inject(AccountsApi);
  private readonly bridge = (window as Window & { MinhaObraNotifications?: NativeNotifications }).MinhaObraNotifications;
  private readonly replies = new Map<number, (reply: DeviceReply) => void>();
  private sequence = 0;
  private installationId?: string;
  private account?: string;
  private authenticated = false;
  private signingOut = false;
  private operation?: Promise<void>;

  readonly available = Boolean(this.bridge);
  readonly enabled = signal(false);
  readonly busy = signal(false);
  readonly message = signal('');

  constructor() {
    if (this.bridge) this.bridge.onmessage = (event) => this.receive(event.data);
  }

  sessionChanged(session: AuthSession | null): void {
    const account = session?.user.id;
    const authenticated = session?.stage === 'AUTHENTICATED';
    if (this.account !== account) {
      this.message.set('');
      this.enabled.set(false);
    }
    this.account = account;
    this.authenticated = authenticated;
    if (authenticated) {
      if (this.operation) void this.operation.then(() => this.sync());
      else this.sync();
    } else if (account && this.bridge) {
      // Clear a previous account even if this one still needs to complete MFA.
      void this.request('installation', account).catch(() => undefined);
    }
  }

  toggle(): void {
    if (!this.account || !this.authenticated || !this.bridge || this.signingOut || this.operation) return;
    const account = this.account;
    void this.run(async () => {
      if (this.enabled()) {
        const id = await this.getInstallation(account);
        await this.api.mutate<void>('DELETE', this.path(id), undefined, undefined, { timeout: 10_000 });
        await this.request('disable', account);
        this.enabled.set(false);
        this.message.set('Avisos desativados neste celular.');
        return;
      }

      const prepared = await this.request('enable', account, 120_000);
      if (prepared.status !== 'ready') {
        this.message.set(this.nativeProblem(prepared.status));
        return;
      }
      await this.register(prepared, account);
      if (this.account !== account) return;
      const confirmed = await this.request('confirm', account);
      this.enabled.set(confirmed.status === 'active');
      if (!this.enabled()) {
        await this.api.mutate<void>('DELETE', this.path(prepared.installationId), undefined, undefined, { timeout: 10_000 });
      }
      this.message.set(this.enabled() ? 'Avisos ativados neste celular.' : this.nativeProblem(confirmed.status));
    });
  }

  async prepareLogout(): Promise<string | undefined> {
    if (!this.bridge || !this.account) return undefined;
    this.signingOut = true;
    if (this.operation) await this.operation;
    return this.getInstallation(this.account).catch(() => undefined);
  }

  async finishLogout(success: boolean): Promise<void> {
    if (success && this.bridge && this.account) {
      await this.request('disable', this.account).catch(() => undefined);
      this.enabled.set(false);
      this.message.set('');
    }
    this.signingOut = false;
  }

  private sync(): void {
    if (!this.account || !this.authenticated || !this.bridge || this.signingOut || this.operation) return;
    const account = this.account;
    void this.run(async () => {
      const reply = await this.request('status', account);
      if (this.account !== account) return;
      this.enabled.set(reply.status !== 'inactive');
      if (reply.status === 'active') await this.register(reply, account);
      else if (reply.status !== 'inactive') this.message.set(this.nativeProblem(reply.status));
    });
  }

  private run(work: () => Promise<void>): Promise<void> {
    this.busy.set(true);
    this.message.set('');
    this.operation = work().catch(async (error: unknown) => {
      await this.api.recoverCsrf(error);
      this.message.set(apiMessage(error, 'Não foi possível atualizar os avisos neste celular.'));
    }).finally(() => {
      this.operation = undefined;
      this.busy.set(false);
    });
    return this.operation;
  }

  private async register(reply: DeviceReply, account: string): Promise<void> {
    if (this.account !== account || !this.authenticated) return;
    if (!reply.token || !reply.timeZone) throw new Error('Invalid notification registration');
    await this.api.mutate<void>('PUT', this.path(reply.installationId),
      { token: reply.token, timeZone: reply.timeZone }, undefined, { timeout: 10_000 });
  }

  private async getInstallation(account: string): Promise<string> {
    return this.installationId ?? (await this.request('installation', account)).installationId;
  }

  private path(id: string): string {
    return '/notifications/devices/' + id;
  }

  private request(action: string, accountId: string, timeout = 10_000): Promise<DeviceReply> {
    const id = ++this.sequence;
    return new Promise((resolve, reject) => {
      const timer = window.setTimeout(() => {
        this.replies.delete(id);
        reject(new Error('Notification bridge timed out'));
      }, timeout);
      this.replies.set(id, (reply) => {
        clearTimeout(timer);
        resolve(reply);
      });
      try {
        this.bridge?.postMessage(JSON.stringify({ id, action, accountId }));
      } catch (error) {
        clearTimeout(timer);
        this.replies.delete(id);
        reject(error);
      }
    });
  }

  private receive(value: string): void {
    try {
      const message = JSON.parse(value) as DeviceReply & { refresh?: boolean };
      if (message.refresh) {
        this.sync();
        return;
      }
      const receive = this.replies.get(message.id);
      if (receive && uuid.test(message.installationId)) {
        this.installationId = message.installationId;
        this.replies.delete(message.id);
        receive(message);
      }
    } catch {
      // Ignore invalid bridge responses.
    }
  }

  private nativeProblem(status: DeviceReply['status']): string {
    return status === 'permission'
      ? 'Permita as notificações nas configurações do Android e tente novamente.'
      : 'O serviço de notificações do Android está indisponível. Tente novamente em instantes.';
  }
}
