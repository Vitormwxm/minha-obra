const MAX_CENTS = 10_000_000_000;

export function parseBrl(value: string): number | null {
  const normalized = value.trim().replace(/^R\$\s*/, '');
  const grouped = /^(\d{1,3}(?:\.\d{3})+)(?:,(\d{1,2}))?$/.exec(normalized);
  const match = grouped ?? /^(\d+)(?:[,.](\d{1,2}))?$/.exec(normalized);
  if (!match) return null;
  const cents = Number(match[1].replace(/\./g, '')) * 100 + Number((match[2] ?? '').padEnd(2, '0'));
  return Number.isSafeInteger(cents) && cents <= MAX_CENTS ? cents : null;
}

export function parsePercent(value: string): string | null {
  const normalized = value.trim().replace(',', '.').replace(/^0+(?=\d)/, '');
  if (!/^\d{1,3}(?:\.\d{1,6})?$/.test(normalized) || Number(normalized) > 100) return null;
  return normalized;
}

export function validMonth(value: string): boolean {
  return /^(?:19\d{2}|20\d{2}|2100)-(?:0[1-9]|1[0-2])$/.test(value);
}

export function validDate(value: string): boolean {
  if (!validMonth(value.slice(0, 7)) || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const date = new Date(value + 'T00:00:00Z');
  return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 10) === value;
}
