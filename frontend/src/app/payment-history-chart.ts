import { Component, computed, input } from '@angular/core';
import type { LedgerMonth } from './finance-api';

const currency = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' });
const monthLabel = new Intl.DateTimeFormat('pt-BR', { month: 'long', year: 'numeric', timeZone: 'UTC' });

@Component({
  selector: 'app-payment-history-chart',
  templateUrl: './payment-history-chart.html',
  styleUrl: './payment-history-chart.css',
})
export class PaymentHistoryChart {
  readonly months = input.required<readonly LedgerMonth[]>();

  readonly bars = computed(() => {
    const months = this.months().filter(month => month.payments.length > 0);
    const max = months.reduce((largest, month) => Math.max(largest,
      (month.caixaPaidCents ?? 0) + (month.builderPaidCents ?? 0)), 1);

    return months.map(month => {
      const caixa = month.caixaPaidCents ?? 0;
      const builder = month.builderPaidCents ?? 0;
      return {
        month: month.month,
        label: month.month.slice(5) + '/' + month.month.slice(0, 4),
        accessibleMonth: monthLabel.format(new Date(month.month + '-01T00:00:00Z')),
        total: currency.format((caixa + builder) / 100),
        caixa: caixa * 100 / max,
        builder: builder * 100 / max,
        caixaValue: currency.format(caixa / 100),
        builderValue: currency.format(builder / 100),
      };
    });
  });
}
