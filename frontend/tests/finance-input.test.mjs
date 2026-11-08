import assert from 'node:assert/strict';
import test from 'node:test';
import { parseBrl, parseNominalRate, validDate, validMonth } from '../src/app/finance-input.ts';

test('BRL text yields integer cents without rounding or treating empty as zero', () => {
  assert.equal(parseBrl(' R$ 1.234,56 '), 123456);
  assert.equal(parseBrl('0,01'), 1);
  assert.equal(parseBrl('2.9'), 290);
  assert.equal(parseBrl('0'), 0);
  assert.equal(parseBrl('100.000.000,00'), 10_000_000_000);
  for (const invalid of ['', '-0,01', '1,234', '12.34,56', '100.000.000,01', '1e3']) {
    assert.equal(parseBrl(invalid), null, invalid);
  }
});

test('annual rate text uses the canonical decimal string expected by the API', () => {
  assert.equal(parseNominalRate(' 07,123456 '), '7.123456');
  assert.equal(parseNominalRate('00,5'), '0.5');
  assert.equal(parseNominalRate('100'), '100');
  for (const invalid of ['', '-0,01', '100.000001', '8,1234567', '7%', '1e2']) {
    assert.equal(parseNominalRate(invalid), null, invalid);
  }
});

test('month and date validation match the API calendar and limits', () => {
  assert.equal(validMonth('1900-01'), true);
  assert.equal(validMonth('2100-12'), true);
  assert.equal(validMonth('2026-11'), true);
  assert.equal(validMonth('2026-1'), false);
  assert.equal(validMonth('2101-01'), false);
  assert.equal(validDate('2000-02-29'), true);
  assert.equal(validDate('2100-02-29'), false);
  assert.equal(validDate('2026-11-31'), false);
  assert.equal(validDate('2026-11-00'), false);
});
