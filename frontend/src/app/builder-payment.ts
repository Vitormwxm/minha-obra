import type { BuilderPaymentCategory, Payment } from './finance-api';

export const builderPaymentLabels: Record<BuilderPaymentCategory, string> = {
  MONTHLY: 'Parcela mensal',
  INTERCALARY: 'Intercalada',
  DOWN_PAYMENT: 'Sinal / entrada',
  UNCLASSIFIED: 'Sem classificação',
};

export const paymentHistorySeries = [
  { key: 'CAIXA', label: 'CAIXA', color: '#087f72' },
  { key: 'MONTHLY', label: builderPaymentLabels.MONTHLY, color: '#d69b40' },
  { key: 'INTERCALARY', label: builderPaymentLabels.INTERCALARY, color: '#4275b5' },
  { key: 'DOWN_PAYMENT', label: builderPaymentLabels.DOWN_PAYMENT, color: '#9b4e6a' },
  { key: 'UNCLASSIFIED', label: builderPaymentLabels.UNCLASSIFIED, color: '#737c77' },
] as const;

export function builderPaymentBreakdown(payments: readonly Payment[]): Record<BuilderPaymentCategory, number> {
  const totals = { MONTHLY: 0, INTERCALARY: 0, DOWN_PAYMENT: 0, UNCLASSIFIED: 0 };
  for (const payment of payments) {
    if (payment.kind === 'CONSTRUTORA') {
      totals[payment.builderCategory ?? 'UNCLASSIFIED'] += payment.paidCents;
    }
  }
  return totals;
}
