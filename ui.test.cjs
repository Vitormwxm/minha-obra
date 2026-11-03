'use strict';

// Optional interface QA. Supply jsdom with NODE_PATH; the application itself has
// no runtime dependency on Node, jsdom or another project.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { JSDOM } = require('jsdom');

const folder = __dirname;
const storageKey = 'minha-obra.simulacao.v1';
const tick = () => new Promise(resolve => setImmediate(resolve));

function mount(options = {}) {
  const dom = new JSDOM(fs.readFileSync(path.join(folder, 'index.html'), 'utf8'), {
    url: 'https://simulacao.local/', runScripts: 'outside-only'
  });
  const { window } = dom;
  const downloads = [];
  const errors = [];
  window.addEventListener('error', event => errors.push(event.error || event.message));
  window.Blob = class { constructor(parts, settings) { this.content = parts.join(''); this.type = settings.type; } };
  window.URL.createObjectURL = blob => { downloads.push({ blob }); return `blob:qa-${downloads.length}`; };
  window.URL.revokeObjectURL = () => {};
  window.HTMLAnchorElement.prototype.click = function () { downloads.at(-1).filename = this.download; };
  window.print = () => {};
  if (options.android) window.Android = options.android;
  if (options.saved) window.localStorage.setItem(storageKey, JSON.stringify(options.saved));
  if (options.storageBlocked) Object.defineProperty(window, 'localStorage', {
    get() { throw new window.DOMException('Storage blocked', 'SecurityError'); }
  });
  window.eval(fs.readFileSync(path.join(folder, 'calculator.js'), 'utf8'));
  window.eval(fs.readFileSync(path.join(folder, 'app.js'), 'utf8'));
  const get = id => window.document.getElementById(id);
  const input = (id, value) => {
    get(id).value = value;
    get(id).dispatchEvent(new window.Event('input', { bubbles: true }));
  };
  const submit = () => get('simulation-form').dispatchEvent(new window.Event('submit', { bubbles: true, cancelable: true }));
  const load = async (data, size) => {
    const content = typeof data === 'string' ? data : JSON.stringify(data);
    Object.defineProperty(get('import-file'), 'files', { configurable: true, value: [{ size: size ?? content.length, text: async () => content }] });
    get('import-file').dispatchEvent(new window.Event('change', { bubbles: true }));
    await tick();
  };
  return { window, get, input, submit, load, downloads, errors, close: () => dom.window.close() };
}

function exampleInput(overrides = {}) {
  return { balance: 100000, annualRate: 8, trRate: 0.2, insurance: 30, fee: 25,
    startMonth: '2026-11', endMonth: '2027-10', remainingRelease: 60000,
    releaseMode: 'equal', releaseTiming: 'end', monthlyOverrides: [], ...overrides };
}

function setup(t, options) {
  const app = mount(options);
  t.after(() => {
    app.close();
    assert.deepEqual(app.errors, [], 'No uncaught interface errors');
  });
  return app;
}

test('Brazilian money and rate parsing rejects malformed numbers', t => {
  const app = setup(t);
  const parse = app.window.MinhaObraUI.parseNumber;
  assert.equal(parse('100.000,50'), 100000.5);
  assert.equal(parse('1.000'), 1000);
  assert.equal(parse('0,123456', 'rate'), 0.123456);
  assert.equal(parse('8.000', 'rate'), 8);
  assert.ok(Number.isNaN(parse('1,2,3')));
  assert.ok(Number.isNaN(parse('1.23,45')));
  assert.ok(Number.isNaN(parse('')));
});

test('example calculates 923 BRL and input immediately disables stale exports', t => {
  const app = setup(t);
  app.get('load-example').click();
  assert.match(app.get('first-payment').textContent, /923,00/);
  assert.equal(app.get('projection-body').rows.length, 12);
  assert.equal(app.get('targetBalance').value, '192.000,00');
  assert.equal(app.get('remainingRelease').value, '92.000,00');
  assert.equal(app.get('remainingRelease').readOnly, true);
  assert.match(app.get('final-balance').textContent, /192\.000,00/);
  assert.match(app.get('projection-body').rows[11].cells[9].textContent, /192\.000,00/);
  const table = app.get('projection-body').closest('table');
  assert.equal(table.tHead.rows[0].cells[3].textContent, 'TR (% a.m.)');
  assert.equal(table.tHead.rows[0].cells[4].textContent, 'Correção TR (R$)');
  assert.equal(table.tHead.rows[0].cells[5].textContent, 'Juros (R$)');
  assert.match(table.tBodies[0].rows[0].cells[3].textContent, /0,2%/);
  assert.match(table.tBodies[0].rows[0].cells[4].textContent, /200,00/);
  assert.match(table.tBodies[0].rows[0].cells[5].textContent, /668,00/);
  assert.equal(app.get('export-csv').disabled, false);
  app.input('balance', 'inválido');
  assert.equal(app.get('result-area').hidden, true);
  for (const id of ['export-csv', 'save-file', 'print-report']) assert.equal(app.get(id).disabled, true);
  app.submit();
  assert.equal(app.get('balance').getAttribute('aria-invalid'), 'true');
  app.input('targetBalance', '292.000,00');
  app.input('balance', '200.000,00');
  app.submit();
  assert.match(app.get('first-payment').textContent, /1\.791,00/);
});

test('debounced recalculation and monthly custom releases reflect edited controls', async t => {
  const app = setup(t);
  await app.load({ version: 1, input: exampleInput({ endMonth: '2026-12', remainingRelease: 3000 }) });
  app.input('releaseMode', 'custom');
  for (const [month, amount] of [['2026-11', '1.000,00'], ['2026-12', '2.000,00']]) {
    const field = app.window.document.querySelector(`[data-month="${month}"][data-field="release"]`);
    assert.equal(field.disabled, false);
    field.value = amount;
    field.dispatchEvent(new app.window.Event('input', { bubbles: true }));
  }
  await new Promise(resolve => setTimeout(resolve, 400));
  assert.equal(app.get('form-errors').hidden, true);
  assert.equal(app.get('result-area').hidden, false);
  assert.match(app.get('final-balance').textContent, /103\.000,00/);
  assert.match(app.get('projection-body').rows[0].cells[2].textContent, /1\.000,00/);
  app.input('releaseMode', 'equal');
  app.input('targetBalance', '100.000,00');
  app.submit();
  assert.equal(app.get('remainingRelease').readOnly, true);
  assert.equal(app.get('remainingRelease').value, '0,00');
  assert.match(app.get('final-balance').textContent, /100\.000,00/);
});

test('invalid and oversized imports preserve the preceding simulation', async t => {
  const app = setup(t);
  app.get('load-example').click();
  const previous = { balance: app.get('balance').value, first: app.get('first-payment').textContent };
  for (const document of [null, '{', { version: 2, input: exampleInput() },
    { version: 1, input: exampleInput({ balance: 1000000001 }) },
    { version: 1, input: exampleInput({ endMonth: '2040-11' }) }]) {
    await app.load(document);
    assert.equal(app.get('balance').value, previous.balance);
    assert.equal(app.get('first-payment').textContent, previous.first);
    assert.equal(app.get('export-csv').disabled, false);
    assert.match(app.get('status-msg').textContent, /preservados/);
  }
  await app.load({ version: 1, input: exampleInput({ balance: 1 }) }, 1000001);
  assert.equal(app.get('balance').value, previous.balance);
});

test('import preserves all six rate decimal places and normalizes optional fields', async t => {
  const app = setup(t);
  const input = exampleInput({ balance: 1000000000, annualRate: 0, trRate: 0.000001,
    insurance: 0, fee: 0, endMonth: '2026-11', remainingRelease: 0, releaseMode: 'none' });
  await app.load({ version: 1, input });
  assert.equal(app.get('trRate').value, '0,000001');
  assert.match(app.get('first-payment').textContent, /10,00/);
  delete input.remainingRelease;
  delete input.releaseMode;
  delete input.releaseTiming;
  delete input.monthlyOverrides;
  await app.load({ version: 1, input });
  assert.equal(app.get('form-errors').hidden, true);
  assert.equal(app.get('releaseMode').value, 'equal');
  assert.equal(app.get('releaseTiming').value, 'end');
  assert.match(app.get('first-payment').textContent, /10,00/);
});

test('CSV includes Brazilian decimal values and JSON export can be reimported', async t => {
  const app = setup(t);
  app.get('load-example').click();
  app.get('export-csv').click();
  const csv = app.downloads[0];
  assert.match(csv.filename, /\.csv$/);
  assert.ok(csv.blob.content.startsWith('\uFEFF'));
  assert.ok(csv.blob.content.includes('"923,00"'));
  assert.ok(csv.blob.content.includes('"TOTAL";'));
  app.get('save-file').click();
  const saved = JSON.parse(app.downloads[1].blob.content);
  assert.equal(saved.version, 1);
  assert.equal(saved.input.balance, 100000);
  app.get('clear-all').click();
  await app.load(saved);
  assert.match(app.get('first-payment').textContent, /923,00/);
});

test('local persistence requires opt-in and remains optional when blocked', t => {
  const app = setup(t);
  app.get('load-example').click();
  assert.equal(app.window.localStorage.getItem(storageKey), null);
  app.get('remember-data').checked = true;
  app.get('remember-data').dispatchEvent(new app.window.Event('change'));
  const saved = JSON.parse(app.window.localStorage.getItem(storageKey));
  assert.equal(saved.input.balance, 100000);
  const restored = setup(t, { saved });
  assert.equal(restored.get('remember-data').checked, true);
  assert.match(restored.get('first-payment').textContent, /923,00/);
  app.get('remember-data').checked = false;
  app.get('remember-data').dispatchEvent(new app.window.Event('change'));
  assert.equal(app.window.localStorage.getItem(storageKey), null);
  const blocked = setup(t, { storageBlocked: true });
  blocked.get('load-example').click();
  assert.match(blocked.get('first-payment').textContent, /923,00/);
  assert.equal(blocked.get('export-csv').disabled, false);
});

test('target balance controls the monthly projection and rejects a target below the current balance', t => {
  const app = setup(t);
  assert.equal(app.get('targetBalance').value, '192.000,00');
  assert.equal(app.get('releaseMode').value, 'equal');
  app.get('load-example').click();
  app.input('targetBalance', '210.000,01');
  app.submit();
  assert.equal(app.get('form-errors').hidden, true);
  assert.match(app.get('projection-body').rows[11].cells[9].textContent, /210\.000,01/);
  assert.equal(app.get('remainingRelease').value, '110.000,01');
  app.input('targetBalance', '99.999,99');
  app.submit();
  assert.equal(app.get('result-area').hidden, true);
  assert.equal(app.get('targetBalance').getAttribute('aria-invalid'), 'true');
  assert.equal(app.get('export-csv').disabled, true);
});

test('mobile navigation opens results after calculation and returns to fields on validation errors', t => {
  const app = setup(t);
  assert.equal(app.window.document.body.dataset.screen, 'simulate');
  app.get('nav-results').click();
  assert.equal(app.window.document.body.dataset.screen, 'results');
  assert.equal(app.get('nav-results').getAttribute('aria-current'), 'page');
  app.get('nav-schedule').click();
  assert.equal(app.window.document.body.dataset.screen, 'schedule');
  app.get('load-example').click();
  assert.equal(app.window.document.body.dataset.screen, 'results');
  app.input('balance', '-1');
  app.submit();
  assert.equal(app.window.document.body.dataset.screen, 'simulate');
  assert.equal(app.get('form-errors').hidden, false);
});

test('Android export and printing use native actions instead of browser downloads', t => {
  const actions = [];
  const app = setup(t, { android: {
    saveFile: (...args) => actions.push(['save', ...args]),
    printReport: () => actions.push(['print'])
  } });
  app.get('load-example').click();
  app.get('export-csv').click();
  assert.equal(actions[0][0], 'save');
  assert.match(actions[0][1], /\.csv$/);
  assert.match(actions[0][2], /923,00/);
  app.get('save-file').click();
  const scenario = JSON.parse(actions[1][2]);
  assert.equal(scenario.input.targetBalance, 192000);
  app.get('print-report').click();
  assert.deepEqual(actions[2], ['print']);
  assert.equal(app.downloads.length, 0);
  app.window.MinhaObraMobile.fileStatus('Arquivo salvo com sucesso.');
  assert.equal(app.get('status-msg').textContent, 'Arquivo salvo com sucesso.');
});
