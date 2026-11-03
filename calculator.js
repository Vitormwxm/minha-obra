/*
 * Monthly construction-phase estimate, with monetary correction PAID in the
 * monthly charge. No amortization, arrears, insurance pricing or daily pro rata.
 * CAIXA reference (section 6):
 * https://www.caixa.gov.br/Downloads/habitacao-documentos-gerais/Cartilha_Juros_Fase_de_Obras.pdf
 * AM = SD * monthly TR / 100; interest = (SD + AM) * nominal annual rate / 1200.
 * Rates are percentages. Money inputs have at most two decimal places.
 */
(function (root, factory) {
  'use strict';
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  if (root) root.ObraCalculator = api;
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';

  const RATE_SCALE = 1000000n;
  const MAX_MONEY_CENTS = 100000000000n; // R$ 1 billion per input field.
  const MAX_RATE = 100000000n; // 100%, stored with six percentage decimal places.
  const MAX_MONTHS = 120;
  const MONEY_FIELDS = ['balance', 'insurance', 'fee'];

  class ValidationError extends Error {
    constructor(errors) {
      super('Revise os dados informados para calcular a projeção.');
      this.name = 'ValidationError';
      this.errors = errors;
    }
  }

  function addError(errors, field, message) {
    if (!errors[field]) errors[field] = [];
    errors[field].push(message);
  }

  function decimal(value, places, maximum, field, errors) {
    if ((typeof value !== 'number' && typeof value !== 'string') ||
        (typeof value === 'number' && !Number.isFinite(value))) {
      addError(errors, field, 'Informe um número válido e não negativo.');
      return 0n;
    }
    const text = String(value).trim();
    const match = /^(\d+)(?:\.(\d+))?$/.exec(text);
    if (!match || (match[2] || '').length > places || text.length > 40) {
      addError(errors, field, `Use um número não negativo com até ${places} casas decimais.`);
      return 0n;
    }
    const scaled = BigInt(match[1]) * (10n ** BigInt(places)) +
      BigInt((match[2] || '').padEnd(places, '0') || '0');
    if (scaled > maximum) {
      addError(errors, field, places === 2 ? 'O valor máximo é R$ 1.000.000.000,00.' : 'A taxa deve estar entre 0% e 100%.');
    }
    return scaled;
  }

  function monthIndex(value, field, errors) {
    const match = typeof value === 'string' && /^(\d{4})-(0[1-9]|1[0-2])$/.exec(value);
    if (!match || Number(match[1]) < 1) {
      addError(errors, field, 'Informe um mês válido no formato AAAA-MM.');
      return null;
    }
    return Number(match[1]) * 12 + Number(match[2]) - 1;
  }

  function monthText(index) {
    return `${String(Math.floor(index / 12)).padStart(4, '0')}-${String(index % 12 + 1).padStart(2, '0')}`;
  }

  function readMonths(start, end, errors) {
    const first = monthIndex(start, 'startMonth', errors);
    const last = monthIndex(end, 'endMonth', errors);
    if (first === null || last === null) return [];
    const count = last - first + 1;
    if (count < 1 || count > MAX_MONTHS) {
      addError(errors, 'endMonth', 'O período deve conter de 1 a 120 meses, incluindo o mês da entrega.');
      return [];
    }
    return Array.from({ length: count }, (_, offset) => monthText(first + offset));
  }

  function monthsBetween(start, end) {
    const errors = {};
    const months = readMonths(start, end, errors);
    if (Object.keys(errors).length) throw new ValidationError(errors);
    return months;
  }

  function normalize(input) {
    const errors = {};
    if (!input || typeof input !== 'object' || Array.isArray(input)) {
      return { errors: { input: ['Informe os dados da simulação.'] } };
    }
    const normalized = { money: {}, rates: {}, overrides: new Map() };
    for (const field of MONEY_FIELDS) {
      normalized.money[field] = decimal(input[field], 2, MAX_MONEY_CENTS, field, errors);
    }
    const hasRemainingRelease = input.remainingRelease !== undefined;
    const hasTargetBalance = input.targetBalance !== undefined;
    normalized.money.remainingRelease = decimal(hasRemainingRelease ? input.remainingRelease : 0,
      2, MAX_MONEY_CENTS, 'remainingRelease', errors);
    if (hasTargetBalance) {
      normalized.money.targetBalance = decimal(input.targetBalance, 2, MAX_MONEY_CENTS, 'targetBalance', errors);
      if (!errors.balance && !errors.targetBalance) {
        if (normalized.money.targetBalance < normalized.money.balance) {
          addError(errors, 'targetBalance', 'O saldo final deve ser maior ou igual ao saldo atual já liberado.');
        } else {
          const difference = normalized.money.targetBalance - normalized.money.balance;
          if (hasRemainingRelease && !errors.remainingRelease && normalized.money.remainingRelease !== difference) {
            addError(errors, 'remainingRelease', 'O valor restante deve corresponder à diferença entre o saldo final e o saldo atual.');
          }
          normalized.money.remainingRelease = difference;
        }
      }
    } else {
      // Preserve saved scenarios created before the target-balance field existed.
      normalized.money.targetBalance = normalized.money.balance + normalized.money.remainingRelease;
    }
    for (const field of ['annualRate', 'trRate']) {
      normalized.rates[field] = decimal(input[field], 6, MAX_RATE, field, errors);
    }
    normalized.months = readMonths(input.startMonth, input.endMonth, errors);
    normalized.releaseMode = input.releaseMode === undefined ? (hasTargetBalance ? 'equal' : 'none') : input.releaseMode;
    if (!['none', 'equal', 'custom'].includes(normalized.releaseMode)) {
      addError(errors, 'releaseMode', 'Escolha nenhuma liberação, parcelas iguais ou cronograma personalizado.');
    }
    normalized.releaseTiming = input.releaseTiming === undefined ? 'end' : input.releaseTiming;
    if (!['start', 'end'].includes(normalized.releaseTiming)) {
      addError(errors, 'releaseTiming', 'Escolha liberação no início ou no fim do mês.');
    }
    const overrides = input.monthlyOverrides === undefined ? [] : input.monthlyOverrides;
    if (!Array.isArray(overrides)) {
      addError(errors, 'monthlyOverrides', 'As alterações mensais devem ser uma lista.');
    } else if (overrides.length > MAX_MONTHS) {
      addError(errors, 'monthlyOverrides', 'Informe no máximo 120 alterações mensais.');
    } else {
      const validMonths = new Set(normalized.months);
      overrides.forEach((row, index) => {
        const prefix = `monthlyOverrides[${index}]`;
        if (!row || typeof row !== 'object' || Array.isArray(row)) {
          addError(errors, prefix, 'Informe uma alteração mensal válida.');
          return;
        }
        monthIndex(row.month, `${prefix}.month`, errors);
        if (!validMonths.has(row.month)) {
          addError(errors, `${prefix}.month`, 'O mês deve pertencer ao período simulado.');
        }
        if (normalized.overrides.has(row.month)) {
          addError(errors, `${prefix}.month`, 'Este mês já possui uma alteração.');
        }
        const entry = {};
        if (Object.prototype.hasOwnProperty.call(row, 'release') && normalized.releaseMode !== 'custom') {
          addError(errors, `${prefix}.release`, 'Use o cronograma personalizado para alterar liberações mensais.');
        }
        for (const field of ['release', 'insurance', 'fee']) {
          if (Object.prototype.hasOwnProperty.call(row, field)) {
            entry[field] = decimal(row[field], 2, MAX_MONEY_CENTS, `${prefix}.${field}`, errors);
          }
        }
        if (Object.prototype.hasOwnProperty.call(row, 'trRate')) {
          entry.trRate = decimal(row.trRate, 6, MAX_RATE, `${prefix}.trRate`, errors);
        }
        normalized.overrides.set(row.month, entry);
      });
    }
    if (Object.keys(errors).length) return { errors };

    const count = BigInt(normalized.months.length);
    const total = normalized.money.remainingRelease;
    const equalRelease = total / count;
    normalized.releases = normalized.months.map((month, index) => {
      const override = normalized.overrides.get(month) || {};
      let release = 0n;
      if (normalized.releaseMode === 'equal') {
        release = index === normalized.months.length - 1 ? total - equalRelease * (count - 1n) : equalRelease;
      }
      if (override.release !== undefined) release = override.release;
      return release;
    });
    const scheduled = normalized.releases.reduce((sum, value) => sum + value, 0n);
    if (normalized.releaseMode === 'none' && (total !== 0n || scheduled !== 0n)) {
      addError(errors, 'remainingRelease', 'Sem novas liberações, o valor restante e o cronograma devem ser zero.');
    } else if (scheduled !== total) {
      addError(errors, 'monthlyOverrides', 'A soma das liberações deve ser igual ao valor restante a liberar.');
    }
    return { errors, normalized };
  }

  function validate(input) {
    const { errors } = normalize(input);
    return { valid: Object.keys(errors).length === 0, errors };
  }

  function roundRatio(numerator, denominator) {
    return (numerator + denominator / 2n) / denominator;
  }

  function money(cents) {
    if (cents < 0n || cents > BigInt(Number.MAX_SAFE_INTEGER)) {
      throw new ValidationError({ input: ['O resultado excede o limite de precisão da simulação.'] });
    }
    return Number(cents) / 100;
  }

  function simulate(input) {
    const { errors, normalized: n } = normalize(input);
    if (Object.keys(errors).length) throw new ValidationError(errors);
    let balance = n.money.balance;
    let highestPayment = 0n;
    const sum = { payment: 0n, interest: 0n, correction: 0n, insurance: 0n, fee: 0n, release: 0n };
    const rows = n.months.map((month, index) => {
      const override = n.overrides.get(month) || {};
      const opening = balance;
      const release = n.releases[index];
      const base = opening + (n.releaseTiming === 'start' ? release : 0n);
      const tr = override.trRate === undefined ? n.rates.trRate : override.trRate;
      const insurance = override.insurance === undefined ? n.money.insurance : override.insurance;
      const fee = override.fee === undefined ? n.money.fee : override.fee;
      const correction = roundRatio(base * tr, 100n * RATE_SCALE);
      const interest = roundRatio((base + correction) * n.rates.annualRate, 1200n * RATE_SCALE);
      const payment = correction + interest + insurance + fee;
      // Correction is paid above, so it is NOT capitalized a second time here.
      balance = opening + release;
      const components = { payment, interest, correction, insurance, fee, release };
      for (const field of Object.keys(sum)) sum[field] += components[field];
      if (payment > highestPayment) highestPayment = payment;
      return {
        month, openingBalance: money(opening), release: money(release), baseBalance: money(base),
        trRate: Number(tr) / Number(RATE_SCALE), correction: money(correction), interest: money(interest),
        insurance: money(insurance), fee: money(fee), payment: money(payment), closingBalance: money(balance)
      };
    });
    const totals = {};
    for (const field of Object.keys(sum)) totals[field] = money(sum[field]);
    return {
      rows, totals,
      firstPayment: rows[0].payment,
      lastPayment: rows[rows.length - 1].payment,
      maxPayment: money(highestPayment),
      averagePayment: money(roundRatio(sum.payment, BigInt(rows.length))),
      finalBalance: money(balance),
      targetBalance: money(n.money.targetBalance),
      monthsCount: rows.length,
      monthlyInterestRate: Number(n.rates.annualRate) / Number(RATE_SCALE) / 12
    };
  }

  return Object.freeze({ simulate, validate, monthsBetween, ValidationError });
});
