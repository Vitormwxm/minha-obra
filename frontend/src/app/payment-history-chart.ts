import { Component, computed, input, signal } from '@angular/core';
import { builderPaymentBreakdown, paymentHistorySeries } from './builder-payment';
import type { LedgerMonth } from './finance-api';

type PaymentFilter = 'all' | 'caixa' | 'builder';

const currency = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' });
const monthLabel = new Intl.DateTimeFormat('pt-BR', { month: 'long', year: 'numeric', timeZone: 'UTC' });

@Component({
  selector: 'app-payment-history-chart',
  templateUrl: './payment-history-chart.html',
  styleUrl: './payment-history-chart.css',
})
export class PaymentHistoryChart {
  readonly months = input.required<readonly LedgerMonth[]>();
  readonly filter = signal<PaymentFilter>('all');
  readonly showIntercalary = signal(true);
  readonly showDownPayment = signal(false);
  readonly paidMonths = computed(() => this.months().filter(month => month.payments.length > 0));
  readonly totals = computed(() => this.paidMonths().reduce(
    (totals, month) => ({
      caixa: totals.caixa + (month.caixaPaidCents ?? 0),
      builder: totals.builder + (month.builderPaidCents ?? 0),
    }),
    { caixa: 0, builder: 0 },
  ));
  readonly downloading = signal(false);
  readonly downloadError = signal('');
  readonly amount = (cents: number) => currency.format(cents / 100);
  readonly caixaShare = computed(() => {
    const { caixa, builder } = this.totals();
    return caixa + builder === 0 ? 0 : caixa / (caixa + builder) * 100;
  });
  readonly percentage = (value: number) => value.toLocaleString('pt-BR', { maximumFractionDigits: 1 }) + '%';

  readonly paidBreakdowns = computed(() => this.paidMonths().map(month => ({
    month: month.month,
    amounts: { CAIXA: month.caixaPaidCents ?? 0, ...builderPaymentBreakdown(month.payments) },
  })));

  readonly visibleSeries = computed(() => paymentHistorySeries.filter(({ key }) =>
    (key === 'CAIXA' ? this.filter() !== 'builder' : this.filter() !== 'caixa')
      && (key !== 'INTERCALARY' || this.showIntercalary())
      && (key !== 'DOWN_PAYMENT' || this.showDownPayment())
      && this.paidBreakdowns().some(row => row.amounts[key] > 0),
  ));

  readonly bars = computed(() => {
    const series = this.visibleSeries();
    const months = this.paidBreakdowns().map(row => {
      const segments = series.map(item => ({ ...item, cents: row.amounts[item.key] })).filter(item => item.cents > 0);
      return { month: row.month, segments, total: segments.reduce((sum, item) => sum + item.cents, 0) };
    }).filter(row => row.total > 0);
    const max = months.reduce((largest, row) => Math.max(largest, row.total), 1);

    return months.map(({ month, segments, total }) => {
      const details = segments.map(item => item.label + ', ' + this.amount(item.cents));
      if (details.length > 1) details.push('total, ' + this.amount(total));
      return {
        month,
        label: month.slice(5) + '/' + month.slice(0, 4),
        accessibleLabel: monthLabel.format(new Date(month + '-01T00:00:00Z')) + ': ' + details.join('; '),
        total: this.amount(total),
        segments: segments.map(item => ({ ...item, height: item.cents * 100 / max })),
      };
    });
  });

  async downloadHistory(): Promise<void> {
    if (this.downloading() || !this.paidMonths().length) return;

    const android = (window as Window & { MinhaObraPaymentPdf?: { postMessage(data: string): void } }).MinhaObraPaymentPdf;
    if (!android && navigator.userAgent.includes('MinhaObraAndroid/')) {
      this.downloadError.set('Atualize o Android System WebView para salvar o PDF no aplicativo.');
      return;
    }
    const months = this.paidMonths();
    const totals = this.totals();
    this.downloadError.set('');
    this.downloading.set(true);
    try {
      const { createPaymentHistoryPdf } = await import('./payment-history-pdf');
      const pdf = createPaymentHistoryPdf(months, totals);
      if (android) {
        android.postMessage(pdf.output('datauristring').split(',')[1]);
      } else {
        pdf.save('minha-obra-pagamentos.pdf');
      }
    } catch {
      this.downloadError.set('Não foi possível gerar o PDF. Tente novamente.');
    } finally {
      this.downloading.set(false);
    }
  }
}
