/** New passwords are strings to preserve leading zeroes. */
export function passwordProblem(value: string): string | null {
  return value.length === 8 && !/[^0-9]/.test(value)
    ? null
    : 'Use uma senha com exatamente 8 dígitos de 0 a 9.';
}

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
  const combinations = 100_000_000;
  const limit = Math.floor(2 ** 32 / combinations) * combinations;
  const random = new Uint32Array(1);
  do {
    crypto.getRandomValues(random);
  } while (random[0]! >= limit);
  return (random[0]! % combinations).toString().padStart(8, '0');
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
