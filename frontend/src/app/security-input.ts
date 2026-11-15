import type { ValidatorFn } from '@angular/forms';

export function passwordProblem(value: string): string | null {
  if ([...value].length < 8 || /^[\p{Z}\s]*$/u.test(value)) return 'Use pelo menos 8 caracteres na senha.';
  if (/\p{Cc}/u.test(value)) return 'Remova as quebras de linha e os caracteres de controle da senha.';
  return new TextEncoder().encode(value).length > 72 ? 'Use uma senha com até 72 bytes.' : null;
}

export const passwordValidator: ValidatorFn = control =>
  passwordProblem(control.value) === null ? null : { passwordPolicy: true };

export function normalizedUsername(value: string): string {
  return value.trim().toLowerCase();
}

export function validUsername(value: string): boolean {
  return /^[a-z0-9][a-z0-9._-]{2,39}$/.test(value);
}

export function newOperationKey(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6]! & 0x0f) | 0x40;
  bytes[8] = (bytes[8]! & 0x3f) | 0x80;
  const hex = Array.from(bytes, (value) => value.toString(16).padStart(2, '0')).join('');
  return [hex.slice(0, 8), hex.slice(8, 12), hex.slice(12, 16), hex.slice(16, 20), hex.slice(20)].join('-');
}

export function newTemporaryPassword(): string {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_';
  return Array.from(crypto.getRandomValues(new Uint8Array(16)), byte => alphabet[byte & 63]).join('');
}

/**
 * Keep only a request digest and its idempotency key in memory.
 * A network retry of the same operation must use the same key.
 */
export class OperationKey {
  private previous: { digest: string; key: string } | null = null;

  async forPayload(scope: string, payload: unknown): Promise<string> {
    const bytes = new TextEncoder().encode(JSON.stringify({ scope, payload }));
    const digest = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', bytes)),
      (value) => value.toString(16).padStart(2, '0')).join('');
    if (this.previous?.digest !== digest) {
      this.previous = { digest, key: newOperationKey() };
    }
    return this.previous.key;
  }

  clear(): void {
    this.previous = null;
  }
}
