'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { simulate, validate, monthsBetween, ValidationError } = require('./calculator.js');

const input = (changes = {}) => ({
  balance: 100000, annualRate: 8, trRate: 0.2, insurance: 30, fee: 25,
  startMonth: '2026-11', endMonth: '2026-11', remainingRelease: 0,
  releaseMode: 'none', monthlyOverrides: [], ...changes
});

function invalid(changes, field) {
  const data = input(changes);
  const result = validate(data);
  assert.equal(result.valid, false);
  assert.ok(result.errors[field], `Expected validation error for ${field}`);
  assert.throws(() => simulate(data), error => error instanceof ValidationError && !!error.errors[field]);
}

test('reproduces the disclosed CAIXA formula with nominal annual interest', () => {
  const result = simulate(input());
  assert.deepEqual(result.rows[0], {
    month: '2026-11', openingBalance: 100000, release: 0, baseBalance: 100000,
    trRate: 0.2, correction: 200, interest: 668, insurance: 30, fee: 25,
    payment: 923, closingBalance: 100000
  });
  assert.equal(result.firstPayment, 923);
  assert.equal(result.lastPayment, 923);
  assert.equal(result.monthlyInterestRate, 8 / 12);
});

test('TR paid in the charge is not capitalized again in the next balance', () => {
  const result = simulate(input({ endMonth: '2027-01' }));
  assert.deepEqual(result.rows.map(row => row.payment), [923, 923, 923]);
  assert.deepEqual(result.rows.map(row => row.closingBalance), [100000, 100000, 100000]);
  assert.deepEqual(result.totals, { payment: 2769, interest: 2004, correction: 600, insurance: 90, fee: 75, release: 0 });
  assert.equal(result.averagePayment, 923);
  assert.equal(result.finalBalance, 100000);
});

test('zero rates, balance and fixed charges are valid', () => {
  const result = simulate(input({ balance: 0, annualRate: 0, trRate: 0, insurance: 0, fee: 0 }));
  assert.equal(result.firstPayment, 0);
  assert.equal(result.maxPayment, 0);
  assert.equal(result.finalBalance, 0);
});

test('divides nominal annual rate by twelve, not by an effective-rate root', () => {
  const result = simulate(input({ annualRate: 12, trRate: 0, insurance: 0, fee: 0 }));
  assert.equal(result.monthlyInterestRate, 1);
  assert.equal(result.rows[0].interest, 1000);
});

test('end-of-month releases affect the next charge and leave the last-month remainder', () => {
  const result = simulate(input({ endMonth: '2027-01', remainingRelease: 100, releaseMode: 'equal', annualRate: 12, trRate: 0, insurance: 0, fee: 0 }));
  assert.deepEqual(result.rows.map(row => row.release), [33.33, 33.33, 33.34]);
  assert.deepEqual(result.rows.map(row => row.baseBalance), [100000, 100033.33, 100066.66]);
  assert.deepEqual(result.rows.map(row => row.interest), [1000, 1000.33, 1000.67]);
  assert.equal(result.totals.release, 100);
  assert.equal(result.finalBalance, 100100);
});

test('start-of-month releases are included in the current charge', () => {
  const data = input({ remainingRelease: 10000, releaseMode: 'equal', annualRate: 12 });
  const atEnd = simulate(data);
  const atStart = simulate({ ...data, releaseTiming: 'start' });
  assert.equal(atEnd.firstPayment, 1257);
  assert.equal(atStart.firstPayment, 1377.2);
  assert.equal(atEnd.rows[0].baseBalance, 100000);
  assert.equal(atStart.rows[0].baseBalance, 110000);
  assert.equal(atEnd.finalBalance, atStart.finalBalance);
});

test('custom releases must exhaust the informed remaining release amount', () => {
  const data = input({ balance: 1000, annualRate: 12, trRate: 0, insurance: 0, fee: 0,
    endMonth: '2026-12', remainingRelease: 100, releaseMode: 'custom',
    monthlyOverrides: [{ month: '2026-11', release: 25 }, { month: '2026-12', release: 75 }] });
  const result = simulate(data);
  assert.deepEqual(result.rows.map(row => row.payment), [10, 10.25]);
  assert.equal(result.finalBalance, 1100);
  invalid({ ...data, remainingRelease: 101 }, 'monthlyOverrides');
});

test('monthly overrides apply only to their own month and accept explicit zero', () => {
  const result = simulate(input({ endMonth: '2027-01',
    monthlyOverrides: [{ month: '2026-12', trRate: 0.3, insurance: 0, fee: 50 }] }));
  assert.deepEqual(result.rows.map(row => row.payment), [923, 1018.67, 923]);
  assert.deepEqual(result.rows.map(row => row.insurance), [30, 0, 30]);
  assert.equal(result.maxPayment, 1018.67);
  assert.equal(result.totals.payment, 2864.67);
  assert.equal(result.averagePayment, 954.89);
});

test('money is rounded half up by component using exact integer arithmetic', () => {
  const result = simulate(input({ balance: 0.05, annualRate: 100, trRate: 10, insurance: 0, fee: 0 }));
  assert.equal(result.rows[0].correction, 0.01);
  assert.equal(result.rows[0].interest, 0.01);
  assert.equal(result.rows[0].payment, 0.02);
});

test('component totals sum the charged cents exactly', () => {
  const result = simulate(input({ balance: 199999.99, annualRate: 7.0197, trRate: 0.123456,
    insurance: 33.33, fee: 25.01, endMonth: '2027-10', remainingRelease: 12345.67, releaseMode: 'equal' }));
  const cents = value => Math.round(value * 100);
  for (const key of Object.keys(result.totals)) {
    assert.equal(cents(result.totals[key]), result.rows.reduce((sum, row) => sum + cents(row[key]), 0));
  }
  for (const row of result.rows) {
    assert.equal(cents(row.payment), cents(row.correction) + cents(row.interest) + cents(row.insurance) + cents(row.fee));
  }
  assert.equal(result.finalBalance, 212345.66);
});

test('month range is inclusive, handles year transitions and permits 120 months', () => {
  assert.deepEqual(monthsBetween('2026-12', '2027-02'), ['2026-12', '2027-01', '2027-02']);
  assert.deepEqual(monthsBetween('2024-02', '2024-02'), ['2024-02']);
  assert.deepEqual(monthsBetween('0099-12', '0100-01'), ['0099-12', '0100-01']);
  assert.equal(monthsBetween('2026-01', '2035-12').length, 120);
  assert.throws(() => monthsBetween('2026-01', '2036-01'), ValidationError);
  assert.throws(() => monthsBetween('2027-01', '2026-12'), ValidationError);
});

test('rejects invalid or ambiguous months', () => {
  for (const startMonth of ['2026-13', '2026-00', '2026-1', '0000-01', '2026-11-01', '', null]) {
    invalid({ startMonth }, 'startMonth');
  }
});

test('rejects nonfinite, negative, blank, overprecise and excessive amounts', () => {
  for (const balance of [NaN, Infinity, -1, '', null, true, 1.001, '1,00', 1000000000.01]) {
    invalid({ balance }, 'balance');
  }
  assert.equal(validate(input({ balance: '100000.00' })).valid, true);
});

test('rejects invalid rates and limits percentage precision', () => {
  for (const annualRate of [-1, 100.1, Infinity, '', 1.0000001]) invalid({ annualRate }, 'annualRate');
  for (const trRate of [-0.1, 100.1, NaN, null]) invalid({ trRate }, 'trRate');
});

test('rejects conflicting, duplicate and out-of-range release instructions', () => {
  invalid({ remainingRelease: 1 }, 'remainingRelease');
  invalid({ monthlyOverrides: [{ month: '2026-11', release: 1 }] }, 'monthlyOverrides[0].release');
  invalid({ monthlyOverrides: [{ month: '2026-12', fee: 1 }] }, 'monthlyOverrides[0].month');
  invalid({ monthlyOverrides: [{ month: '2026-11' }, { month: '2026-11' }] }, 'monthlyOverrides[1].month');
  invalid({ monthlyOverrides: null }, 'monthlyOverrides');
  invalid({ releaseTiming: 'middle' }, 'releaseTiming');
  invalid({ releaseMode: 'unknown' }, 'releaseMode');
});

test('release overrides require custom mode even when zero or equal to the planned amount', () => {
  invalid({ monthlyOverrides: [{ month: '2026-11', release: 0 }] }, 'monthlyOverrides[0].release');
  invalid({ releaseMode: 'equal', remainingRelease: 100,
    monthlyOverrides: [{ month: '2026-11', release: 100 }] }, 'monthlyOverrides[0].release');
  invalid({ releaseMode: 'equal', remainingRelease: 0,
    monthlyOverrides: [{ month: '2026-11', release: 0 }] }, 'monthlyOverrides[0].release');
});

test('tiny equal releases preserve cents on the final month', () => {
  const result = simulate(input({ endMonth: '2027-01', releaseMode: 'equal', remainingRelease: 0.01 }));
  assert.deepEqual(result.rows.map(row => row.release), [0, 0, 0.01]);
  assert.equal(result.totals.release, 0.01);
});

test('maximum allowed inputs and 120 months remain finite and cent-safe', () => {
  const result = simulate(input({ balance: 1000000000, insurance: 1000000000, fee: 1000000000,
    annualRate: 100, trRate: 100, startMonth: '2026-01', endMonth: '2035-12',
    releaseMode: 'equal', remainingRelease: 1000000000, releaseTiming: 'start' }));
  assert.equal(result.monthsCount, 120);
  assert.equal(result.finalBalance, 2000000000);
  assert.ok(Number.isFinite(result.totals.payment));
  assert.ok(Number.isSafeInteger(Math.round(result.totals.payment * 100)));
});

test('does not mutate caller data', () => {
  const data = input({ monthlyOverrides: [Object.freeze({ month: '2026-11', insurance: 0 })] });
  Object.freeze(data.monthlyOverrides);
  Object.freeze(data);
  const original = JSON.stringify(data);
  simulate(data);
  validate(data);
  assert.equal(JSON.stringify(data), original);
});

test('works as a classic browser script and does not depend on local Date parsing', () => {
  const sandbox = { Date: class ForbiddenDate { constructor() { throw new Error('Do not parse local dates'); } } };
  vm.createContext(sandbox);
  vm.runInContext(fs.readFileSync(path.join(__dirname, 'calculator.js'), 'utf8'), sandbox);
  assert.equal(sandbox.ObraCalculator.simulate(input()).firstPayment, 923);
  assert.equal(sandbox.ObraCalculator.monthsBetween('2026-12', '2027-02').join(','), '2026-12,2027-01,2027-02');
});

test('invalid root inputs return structured errors rather than internal exceptions', () => {
  for (const value of [null, undefined, [], 'text']) {
    assert.equal(validate(value).valid, false);
    assert.throws(() => simulate(value), error => error instanceof ValidationError && !!error.errors.input);
  }
});

test('a 192000 target derives releases over twelve months and reaches that balance exactly', () => {
  const data = input({ targetBalance: 192000, endMonth: '2027-10' });
  delete data.remainingRelease;
  delete data.releaseMode;
  const result = simulate(data);
  assert.equal(result.monthsCount, 12);
  assert.equal(result.targetBalance, 192000);
  assert.equal(result.totals.release, 92000);
  assert.equal(result.finalBalance, 192000);
  assert.equal(result.rows.at(-1).closingBalance, 192000);
  assert.deepEqual(result.rows.map(row => row.release), [...Array(11).fill(7666.66), 7666.74]);
  assert.ok(result.rows.every(row => row.closingBalance <= 192000));
  assert.ok(result.rows.slice(1).every((row, index) => row.closingBalance > result.rows[index].closingBalance));
});

test('target balance handles a nondivisible cent remainder and unchanged balances', () => {
  const result = simulate(input({ targetBalance: '100000.01', remainingRelease: 0.01,
    releaseMode: 'equal', endMonth: '2027-01' }));
  assert.deepEqual(result.rows.map(row => row.release), [0, 0, 0.01]);
  assert.equal(result.finalBalance, 100000.01);
  assert.equal(result.targetBalance, 100000.01);
  assert.equal(simulate(input({ targetBalance: 100000 })).finalBalance, 100000);
});

test('target balance rejects a smaller balance, inconsistent remaining releases and invalid values', () => {
  invalid({ targetBalance: 99999.99 }, 'targetBalance');
  invalid({ targetBalance: 192000 }, 'remainingRelease');
  invalid({ targetBalance: 192000, remainingRelease: 92000 }, 'remainingRelease');
  for (const targetBalance of [-1, NaN, Infinity, '', null, 192000.001, 1000000000.01]) {
    invalid({ targetBalance }, 'targetBalance');
  }
});

test('custom releases must sum to the difference needed to reach the target', () => {
  const data = input({ targetBalance: 192000, endMonth: '2026-12', releaseMode: 'custom',
    monthlyOverrides: [{ month: '2026-11', release: 20000 }, { month: '2026-12', release: 72000 }] });
  delete data.remainingRelease;
  const result = simulate(data);
  assert.deepEqual(result.rows.map(row => row.closingBalance), [120000, 192000]);
  assert.equal(result.targetBalance, 192000);
  assert.equal(result.totals.release, 92000);
  assert.throws(() => simulate({ ...data, monthlyOverrides: [{ month: '2026-11', release: 20000 }] }),
    error => error instanceof ValidationError && !!error.errors.monthlyOverrides);
});

test('legacy scenarios without a target retain their original result and expose the inferred target', () => {
  const result = simulate(input({ remainingRelease: 60000, releaseMode: 'equal', endMonth: '2027-10' }));
  assert.equal(result.finalBalance, 160000);
  assert.equal(result.targetBalance, 160000);
  assert.equal(result.firstPayment, 923);
});
