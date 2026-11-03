/* Interface local: nenhum dado de contrato é enviado pela aplicação. */
(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const calculator = globalThis.ObraCalculator;
  const numberFields = ['balance', 'targetBalance', 'annualRate', 'trRate', 'insurance', 'fee', 'remainingRelease'];
  const textFields = ['startMonth', 'endMonth', 'releaseMode', 'releaseTiming'];
  const fieldNames = { balance: 'Saldo devedor atual', targetBalance: 'Saldo final do financiamento', annualRate: 'Juros nominais', trRate: 'TR mensal', insurance: 'Seguro mensal', fee: 'Taxas mensais', remainingRelease: 'Valor ainda a liberar', startMonth: 'Primeiro mês', endMonth: 'Entrega da obra', releaseMode: 'Liberações', releaseTiming: 'Momento da liberação', monthlyOverrides: 'Ajustes mensais' };
  const storageKey = 'minha-obra.simulacao.v1';
  const currency = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' });
  const numeric = new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 6 });
  const moneyInput = new Intl.NumberFormat('pt-BR', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  let lastResult = null;
  let overrides = new Map();
  let refreshTimer;
  let loading = false;

  function parseNumber(value, kind = 'currency') {
    const raw = String(value).trim().replace(/\s/g, '');
    if (!raw) return NaN;
    let canonical = raw;
    if (raw.includes(',')) {
      if (!/^-?(?:\d+|\d{1,3}(?:\.\d{3})+),\d+$/.test(raw)) return NaN;
      canonical = raw.replace(/\./g, '').replace(',', '.');
    } else if (kind === 'currency' && /^-?\d{1,3}(?:\.\d{3})+$/.test(raw)) {
      canonical = raw.replace(/\./g, '');
    } else if (!/^-?\d+(?:\.\d+)?$/.test(raw)) return NaN;
    const result = Number(canonical);
    return Number.isFinite(result) ? result : NaN;
  }

  function labelMonth(month, short = false) {
    const [year, index] = month.split('-').map(Number);
    const date = new Date(0);
    date.setUTCFullYear(year, index - 1, 1);
    return new Intl.DateTimeFormat('pt-BR', { month: short ? 'short' : 'long', year: 'numeric', timeZone: 'UTC' })
      .format(date);
  }
  function currentMonth() {
    const now = new Date();
    return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
  }
  function offsetMonth(month, count) {
    const [year, index] = month.split('-').map(Number);
    const date = new Date(Date.UTC(year, index - 1 + count, 1));
    return `${date.getUTCFullYear()}-${String(date.getUTCMonth() + 1).padStart(2, '0')}`;
  }
  function setStatus(message) { $('status-msg').textContent = message; }
  function showScreen(screen) {
    if (!['simulate', 'results', 'schedule'].includes(screen)) return;
    document.body.dataset.screen = screen;
    document.querySelectorAll('button[data-screen]').forEach(button => {
      if (button.dataset.screen === screen) button.setAttribute('aria-current', 'page');
      else button.removeAttribute('aria-current');
    });
    if (document.activeElement instanceof HTMLElement) document.activeElement.blur();
    document.documentElement.scrollTop = 0;
    document.body.scrollTop = 0;
  }
  document.querySelectorAll('button[data-screen]').forEach(button => {
    button.addEventListener('click', () => showScreen(button.dataset.screen));
  });
  function readInput() {
    const input = {};
    for (const id of numberFields) input[id] = parseNumber($(id).value, $(id).dataset.kind);
    for (const id of textFields) input[id] = $(id).value;
    input.remainingRelease = (Math.round(input.targetBalance * 100) - Math.round(input.balance * 100)) / 100;
    const months = new Set(safeMonths());
    input.monthlyOverrides = [...overrides].filter(([month]) => months.has(month)).map(([month, values]) => {
      const row = { month };
      for (const [key, value] of Object.entries(values)) {
        if (key === 'release' && input.releaseMode !== 'custom') continue;
        if (String(value).trim()) row[key] = parseNumber(value, key === 'trRate' ? 'rate' : 'currency');
      }
      return row;
    });
    return input;
  }
  function safeMonths() {
    try { return calculator.monthsBetween($('startMonth').value, $('endMonth').value); }
    catch { return []; }
  }

  function clearErrors() {
    $('form-errors').replaceChildren();
    $('form-errors').hidden = true;
    document.querySelectorAll('[aria-invalid="true"]').forEach(node => {
      node.removeAttribute('aria-invalid');
      node.classList.remove('field-invalid');
    });
  }
  function showErrors(errors, focus) {
    clearErrors();
    const heading = document.createElement('strong');
    heading.textContent = 'Confira os campos para calcular:';
    const list = document.createElement('ul');
    for (const [key, messages] of Object.entries(errors)) {
      const item = document.createElement('li');
      const monthly = /^monthlyOverrides\[(\d+)\](?:\.(\w+))?$/.exec(key);
      const month = monthly ? readInput().monthlyOverrides[Number(monthly[1])]?.month : null;
      const title = month ? `${monthly[2] === 'release' ? 'Liberação' : fieldNames[monthly[2]] || 'Ajuste'} em ${labelMonth(month)}` : fieldNames[key] || 'Ajustes mensais';
      item.textContent = `${title}: ${[].concat(messages).join(' ')}`;
      list.append(item);
      const input = month ? [...document.querySelectorAll('[data-month]')].find(node => node.dataset.month === month && node.dataset.field === monthly[2]) : $(key);
      if (input) { input.setAttribute('aria-invalid', 'true'); input.classList.add('field-invalid'); }
    }
    $('form-errors').append(heading, list);
    $('form-errors').hidden = false;
    if (focus) { showScreen('simulate'); $('form-errors').focus(); }
  }
  function invalidate() {
    lastResult = null;
    $('result-area').hidden = true;
    $('empty-state').hidden = false;
    for (const id of ['export-csv', 'save-file', 'print-report']) $(id).disabled = true;
  }
  function refresh(userInitiated = false) {
    clearTimeout(refreshTimer);
    const input = readInput();
    try {
      const result = calculator.simulate(input);
      clearErrors();
      lastResult = { input, result };
      render(result);
      saveLocally(input);
      if (userInitiated) {
        setStatus(`Simulação atualizada: ${result.monthsCount} meses, total de ${currency.format(result.totals.payment)}.`);
        showScreen('results');
      }
      return true;
    } catch (error) {
      invalidate();
      showErrors(error.errors || { monthlyOverrides: ['Não foi possível calcular. Confira os valores informados.'] }, userInitiated);
      return false;
    }
  }

  function render(result) {
    $('empty-state').hidden = true;
    $('result-area').hidden = false;
    const values = { 'total-paid': result.totals.payment, 'average-paid': result.averagePayment,
      'peak-paid': result.maxPayment, 'final-balance': result.finalBalance,
      'first-payment': result.firstPayment, 'last-payment': result.lastPayment };
    for (const [id, value] of Object.entries(values)) $(id).textContent = currency.format(value);
    $('month-count').textContent = `${result.monthsCount} ${result.monthsCount === 1 ? 'mês' : 'meses'}`;
    $('range-label').textContent = `${labelMonth(result.rows[0].month)} a ${labelMonth(result.rows.at(-1).month)}`;
    $('summary-breakdown').replaceChildren();
    for (const [key, title] of [['interest', 'Juros'], ['correction', 'Correção pela TR'], ['insurance', 'Seguros'], ['fee', 'Taxas']]) {
      const item = document.createElement('div'); item.className = 'breakdown-item';
      const name = document.createElement('span'); name.textContent = title;
      const value = document.createElement('strong'); value.textContent = currency.format(result.totals[key]);
      item.append(name, value); $('summary-breakdown').append(item);
    }
    renderChart(result);
    const body = document.createDocumentFragment();
    for (const row of result.rows) {
      const tr = document.createElement('tr');
      const month = document.createElement('th'); month.scope = 'row'; month.textContent = labelMonth(row.month, true); tr.append(month);
      for (const value of [row.baseBalance, row.release]) appendCell(tr, currency.format(value));
      appendCell(tr, `${numeric.format(row.trRate)}%`);
      for (const value of [row.correction, row.interest, row.insurance, row.fee, row.payment, row.closingBalance]) appendCell(tr, currency.format(value));
      body.append(tr);
    }
    $('projection-body').replaceChildren(body);
    if ($('table-totals')) {
      const tr = document.createElement('tr');
      appendCell(tr, 'Total no período'); appendCell(tr, '—'); appendCell(tr, currency.format(result.totals.release)); appendCell(tr, '—');
      for (const value of [result.totals.correction, result.totals.interest, result.totals.insurance, result.totals.fee, result.totals.payment]) appendCell(tr, currency.format(value));
      appendCell(tr, currency.format(result.finalBalance)); $('table-totals').replaceChildren(tr);
    }
    for (const id of ['export-csv', 'save-file', 'print-report']) $(id).disabled = false;
  }
  function appendCell(row, value) { const cell = document.createElement('td'); cell.textContent = value; row.append(cell); }
  function renderChart(result) {
    const chart = document.createElement('div'); chart.className = 'bar-chart';
    chart.setAttribute('aria-label', 'Pagamentos estimados em cada mês. Os mesmos valores estão na tabela.');
    const max = Math.max(result.maxPayment, 1);
    const stride = Math.max(1, Math.ceil(result.rows.length / 8));
    result.rows.forEach((row, index) => {
      const col = document.createElement('div'); col.className = 'chart-column';
      const bar = document.createElement('div'); bar.className = 'chart-bar';
      bar.style.height = `${row.payment / max * 100}%`;
      bar.title = `${labelMonth(row.month)}: ${currency.format(row.payment)}`;
      bar.setAttribute('aria-label', bar.title); bar.tabIndex = 0;
      const value = document.createElement('span'); value.className = 'chart-value'; value.textContent = currency.format(row.payment);
      bar.append(value);
      const label = document.createElement('span'); label.className = 'chart-label';
      label.textContent = index % stride === 0 || index === result.rows.length - 1 ? labelMonth(row.month, true) : '';
      col.append(bar, label); chart.append(col);
    });
    $('chart-container').replaceChildren(chart);
  }

  function renderEditor() {
    const months = safeMonths();
    const custom = $('releaseMode').value === 'custom';
    const table = document.createElement('table'); table.className = 'schedule-table';
    const head = document.createElement('thead');
    const header = document.createElement('tr');
    for (const text of ['Mês', 'Liberação (R$)', 'TR (% a.m.)', 'Seguro (R$)', 'Taxas (R$)']) {
      const th = document.createElement('th'); th.scope = 'col'; th.textContent = text; header.append(th);
    }
    head.append(header); table.append(head);
    const body = document.createElement('tbody');
    for (const month of months) {
      const row = document.createElement('tr');
      const cell = document.createElement('th'); cell.scope = 'row'; cell.textContent = labelMonth(month, true); row.append(cell);
      for (const key of ['release', 'trRate', 'insurance', 'fee']) {
        const td = document.createElement('td');
        const input = document.createElement('input'); input.type = 'text'; input.inputMode = 'decimal';
        input.dataset.month = month; input.dataset.field = key;
        input.setAttribute('aria-label', `${key === 'release' ? 'Liberação' : fieldNames[key]} em ${labelMonth(month)}`);
        input.value = overrides.get(month)?.[key] ?? '';
        input.placeholder = key === 'release' ? (custom ? '0,00' : 'Automática') : 'Usar padrão';
        input.disabled = key === 'release' && !custom;
        td.append(input); row.append(td);
      }
      body.append(row);
    }
    table.append(body);
    $('monthly-inputs').replaceChildren(table);
    updateReleaseHint();
  }
  function updateReleaseHint() {
    const mode = $('releaseMode').value;
    const target = parseNumber($('targetBalance').value);
    const opening = parseNumber($('balance').value);
    const difference = (Math.round(target * 100) - Math.round(opening * 100)) / 100;
    $('remainingRelease').value = Number.isFinite(difference) ? moneyInput.format(difference) : '';
    if (!$('release-hint')) return;
    if (mode === 'equal') {
      const count = safeMonths().length;
      $('release-hint').textContent = Number.isFinite(difference) && difference >= 0 && count
        ? `${currency.format(difference)} serão liberados em ${count} ${count === 1 ? 'mês' : 'meses'}, cerca de ${currency.format(difference / count)} por mês, até atingir ${currency.format(target)}. O último mês ajusta os centavos.`
        : 'Informe o saldo atual, o saldo final e o período. O valor a liberar será calculado automaticamente.';
      return;
    }
    const validMonths = new Set(safeMonths());
    let sum = 0;
    for (const [month, row] of overrides) if (validMonths.has(month)) sum += parseNumber(row.release || '0') || 0;
    const remaining = parseNumber($('remainingRelease').value);
    $('release-hint').textContent = `Liberações informadas: ${currency.format(sum)}. Falta distribuir: ${currency.format(Number.isFinite(remaining) ? remaining - sum : 0)}. Preencha o calendário abaixo.`;
  }

  function saveLocally(input) {
    if (!$('remember-data').checked) return;
    try { localStorage.setItem(storageKey, JSON.stringify({ version: 1, input })); }
    catch { setStatus('O armazenamento local está indisponível. Use “Salvar simulação” para guardar um arquivo.'); }
  }
  function download(name, content, type) {
    if (globalThis.Android && typeof globalThis.Android.saveFile === 'function') {
      globalThis.Android.saveFile(name, content, type);
      return true;
    }
    const url = URL.createObjectURL(new Blob([content], { type }));
    const anchor = document.createElement('a'); anchor.href = url; anchor.download = name;
    document.body.append(anchor); anchor.click(); anchor.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
  function exportCsv() {
    if (!refresh()) return;
    const { input, result } = lastResult;
    const escape = value => `"${String(value).replace(/"/g, '""')}"`;
    const decimal = value => value.toFixed(2).replace('.', ',');
    const lines = [
      ['Minha Obra — estimativa até a entrega'],
      ['Premissa', 'TR paga mensalmente; sem amortização; meses completos; juros nominais / 12'],
      ['Juros nominais (% a.a.)', String(input.annualRate).replace('.', ',')],
      ['Saldo final previsto (R$)', decimal(input.targetBalance)],
      ['Liberações', input.releaseTiming === 'start' ? 'No início do mês' : 'No fim do mês'],
      ['Mês', 'Saldo-base (R$)', 'Liberação (R$)', 'TR (%)', 'Correção TR (R$)', 'Juros (R$)', 'Seguro (R$)', 'Taxas (R$)', 'Pagamento (R$)', 'Saldo final (R$)'],
      ...result.rows.map(row => [row.month, decimal(row.baseBalance), decimal(row.release), String(row.trRate).replace('.', ','), ...[row.correction, row.interest, row.insurance, row.fee, row.payment, row.closingBalance].map(decimal)]),
      ['TOTAL', '', decimal(result.totals.release), '', ...[result.totals.correction, result.totals.interest, result.totals.insurance, result.totals.fee, result.totals.payment, result.finalBalance].map(decimal)]
    ];
    const pending = download(`evolucao-de-obra-${input.startMonth}.csv`, '\uFEFF' + lines.map(line => line.map(escape).join(';')).join('\r\n'), 'text/csv;charset=utf-8');
    setStatus(pending ? 'Escolha onde salvar a planilha no Android.' : 'Planilha CSV exportada. Os valores são estimativas.');
  }
  function normalizeScenario(input) {
    if (!input || typeof input !== 'object' || Array.isArray(input)) return input;
    input = { releaseMode: input.targetBalance === undefined ? 'none' : 'equal', releaseTiming: 'end', monthlyOverrides: [], ...input };
    if (input.targetBalance === undefined) {
      input.targetBalance = (Math.round(Number(input.balance) * 100) + Math.round(Number(input.remainingRelease ?? 0) * 100)) / 100;
    }
    if (input.remainingRelease === undefined) {
      input.remainingRelease = (Math.round(Number(input.targetBalance) * 100) - Math.round(Number(input.balance) * 100)) / 100;
    }
    if (input.releaseMode === 'none' && Number(input.remainingRelease) === 0) input.releaseMode = 'equal';
    return input;
  }
  function applyInput(input) {
    input = normalizeScenario(input);
    loading = true;
    for (const id of numberFields) $(id).value = $(id).dataset.kind === 'rate' ? String(input[id]).replace('.', ',') : moneyInput.format(input[id]);
    for (const id of textFields) $(id).value = input[id];
    overrides = new Map((input.monthlyOverrides || []).map(row => [row.month,
      Object.fromEntries(Object.entries(row).filter(([key]) => key !== 'month').map(([key, value]) => [key, key === 'trRate' ? String(value).replace('.', ',') : moneyInput.format(value)]))]));
    renderEditor(); loading = false; refresh();
  }
  function example() {
    applyInput({ balance: 100000, annualRate: 8, trRate: 0.2, insurance: 30, fee: 25,
      startMonth: currentMonth(), endMonth: offsetMonth(currentMonth(), 11),
      targetBalance: 192000, remainingRelease: 92000, releaseMode: 'equal', releaseTiming: 'end', monthlyOverrides: [] });
    setStatus('Exemplo ilustrativo carregado. Troque os valores pelos dados do seu contrato.');
    showScreen('results');
  }
  function reset() {
    clearTimeout(refreshTimer); $('simulation-form').reset();
    for (const id of numberFields) $(id).value = id === 'targetBalance' ? '192.000,00' : '';
    $('startMonth').value = currentMonth(); $('endMonth').value = offsetMonth(currentMonth(), 11);
    $('releaseMode').value = 'equal'; $('releaseTiming').value = 'end'; $('remember-data').checked = false;
    overrides.clear();
    try { localStorage.removeItem(storageKey); } catch { /* Storage can be disabled. */ }
    clearErrors(); invalidate(); renderEditor(); setStatus('Campos e dados salvos neste aparelho foram limpos.');
    showScreen('simulate');
  }

  $('simulation-form').addEventListener('submit', event => { event.preventDefault(); refresh(true); });
  for (const id of [...numberFields, ...textFields]) {
    $(id).addEventListener('input', () => {
      if (loading) return;
      invalidate(); clearTimeout(refreshTimer); clearErrors();
      if (['startMonth', 'endMonth', 'releaseMode'].includes(id)) renderEditor();
      else updateReleaseHint();
      refreshTimer = setTimeout(() => { if (numberFields.filter(key => key !== 'remainingRelease').every(key => $(key).value.trim())) refresh(); }, 350);
    });
  }
  $('monthly-inputs').addEventListener('input', event => {
    const input = event.target;
    if (!input.dataset.month) return;
    const row = overrides.get(input.dataset.month) || {};
    row[input.dataset.field] = input.value; overrides.set(input.dataset.month, row);
    invalidate(); updateReleaseHint(); clearTimeout(refreshTimer);
    refreshTimer = setTimeout(() => refresh(), 350);
  });
  $('apply-schedule').addEventListener('click', () => refresh(true));
  $('load-example').addEventListener('click', example);
  $('clear-all').addEventListener('click', reset);
  $('export-csv').addEventListener('click', exportCsv);
  $('save-file').addEventListener('click', () => {
    if (!refresh()) return;
    const pending = download('minha-obra-simulacao.json', JSON.stringify({ version: 1, input: lastResult.input }, null, 2), 'application/json');
    setStatus(pending ? 'Escolha onde salvar a simulação no Android.' : 'Simulação salva em arquivo. Use “Abrir simulação” para recuperar os dados.');
  });
  $('load-file').addEventListener('click', () => $('import-file').click());
  $('import-file').addEventListener('change', async event => {
    const file = event.target.files[0];
    if (!file) return;
    try {
      if (file.size > 1000000) throw new Error('Arquivo maior que 1 MB.');
      const data = JSON.parse(await file.text());
      if (data.version !== 1 || !data.input) throw new Error('Formato de simulação desconhecido.');
      const input = normalizeScenario(data.input);
      calculator.simulate(input);
      applyInput(input); setStatus('Simulação importada e recalculada com sucesso.');
    } catch { setStatus('Não foi possível abrir: use um arquivo de simulação válido salvo por este aplicativo. Seus dados atuais foram preservados.'); }
    event.target.value = '';
  });
  $('print-report').addEventListener('click', () => {
    if (!refresh()) return;
    if (globalThis.Android && typeof globalThis.Android.printReport === 'function') globalThis.Android.printReport();
    else window.print();
  });
  $('remember-data').addEventListener('change', () => {
    if ($('remember-data').checked) {
      if (lastResult) saveLocally(lastResult.input);
      setStatus('As simulações válidas serão salvas somente neste aparelho.');
    } else {
      try { localStorage.removeItem(storageKey); } catch { /* Optional storage. */ }
      setStatus('Armazenamento automático desligado.');
    }
  });
  $('startMonth').value = currentMonth(); $('endMonth').value = offsetMonth(currentMonth(), 11);
  invalidate(); renderEditor();
  try {
    const saved = localStorage.getItem(storageKey);
    if (saved) {
      const data = JSON.parse(saved);
      if (data.version === 1 && data.input) {
        const input = normalizeScenario(data.input);
        calculator.simulate(input); $('remember-data').checked = true; applyInput(input);
        setStatus('Simulação recuperada deste aparelho. Confira se as premissas continuam atuais.');
      }
    }
  } catch { setStatus('O armazenamento local não está disponível ou contém dados inválidos. Você pode salvar a simulação em arquivo.'); }
  globalThis.MinhaObraUI = Object.freeze({ parseNumber });
  globalThis.MinhaObraMobile = Object.freeze({ showScreen, fileStatus: setStatus });
})();
