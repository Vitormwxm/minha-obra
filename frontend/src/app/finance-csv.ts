import type { FinanceSnapshot } from './finance-api';

type CsvCell = string | number | null;

const money = new Intl.NumberFormat('pt-BR', {
  useGrouping: false, minimumFractionDigits: 2, maximumFractionDigits: 2,
});
const statusLabels = { OPEN: 'Em aberto', PAID: 'Quitado', NO_PAYMENT: 'Sem pagamento' };
const trOrigins = { OFFICIAL: 'Oficial', ESTIMATED: 'Estimada', MANUAL: 'Manual' };

function reais(cents: number | null): string {
  return cents === null ? '' : money.format(cents / 100);
}

function csv(rows: readonly (readonly CsvCell[])[]): string {
  return '\uFEFF' + rows.map((row) =>
    row.map((value) => '"' + String(value ?? '').replaceAll('"', '""') + '"').join(';'),
  ).join('\r\n') + '\r\n';
}

export function downloadFinanceCsv(ledger: FinanceSnapshot, kind: 'summary' | 'payments'): void {
  const isSummary = kind === 'summary';
  const rows: CsvCell[][] = isSummary ? [
    ['Competência', 'Vencimento CAIXA', 'Situação CAIXA', 'Vencimento construtora', 'Situação construtora',
      'Saldo liberado (R$)', 'Seguro (R$)', 'Taxas (R$)', 'TR (%)', 'Origem da TR', 'CAIXA pago (R$)',
      'Construtora paga (R$)', 'Total mensal (R$)', 'CAIXA acumulado (R$)', 'Construtora acumulada (R$)',
      'Total acumulado (R$)', 'Variação mensal (R$)'],
    ...ledger.months.map((row) => [
      row.month, row.caixaDueDate, statusLabels[row.caixaStatus], row.builderDueDate, statusLabels[row.builderStatus],
      reais(row.releasedCents), reais(row.insuranceCents), reais(row.feesCents),
      row.tr?.rate.replace('.', ',') ?? '', row.tr ? trOrigins[row.tr.origin] : '',
      reais(row.caixaPaidCents), reais(row.builderPaidCents), reais(row.monthlyTotalCents),
      reais(row.caixaAccumulatedCents), reais(row.builderAccumulatedCents), reais(row.accumulatedPaidCents),
      reais(row.monthlyVariationCents),
    ]),
  ] : [
    ['Competência', 'Tipo', 'Data do pagamento', 'Valor pago (R$)', 'Juros e multa incluídos (R$)'],
    ...ledger.months.flatMap((row) => row.payments.map((payment) => [
      row.month, payment.kind === 'CAIXA' ? 'CAIXA' : 'Construtora', payment.paidOn,
      reais(payment.paidCents), reais(payment.lateChargeCents),
    ])),
  ];

  const url = URL.createObjectURL(new Blob([csv(rows)], { type: 'text/csv;charset=utf-8' }));
  const link = document.createElement('a');
  link.href = url;
  link.download = isSummary ? 'minha-obra-resumo-mensal.csv' : 'minha-obra-pagamentos.csv';
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
