import { Component, computed, input, signal } from '@angular/core';
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

  readonly bars = computed(() => {
    const showCaixa = this.filter() !== 'builder';
    const showBuilder = this.filter() !== 'caixa';
    const months = this.paidMonths().map(month => ({
      month: month.month,
      caixa: showCaixa ? month.caixaPaidCents : null,
      builder: showBuilder ? month.builderPaidCents : null,
    })).filter(month => month.caixa !== null || month.builder !== null);
    const max = months.reduce((largest, month) => Math.max(largest,
      (month.caixa ?? 0) + (month.builder ?? 0)), 1);

    return months.map(month => {
      const caixa = month.caixa ?? 0;
      const builder = month.builder ?? 0;
      const total = currency.format((caixa + builder) / 100);
      const details = [];
      if (month.caixa !== null) details.push('CAIXA, ' + currency.format(caixa / 100));
      if (month.builder !== null) details.push('construtora, ' + currency.format(builder / 100));
      if (details.length > 1) details.push('total, ' + total);
      return {
        month: month.month,
        label: month.month.slice(5) + '/' + month.month.slice(0, 4),
        accessibleLabel: monthLabel.format(new Date(month.month + '-01T00:00:00Z')) + ': ' + details.join('; '),
        total,
        caixa: caixa * 100 / max,
        builder: builder * 100 / max,
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
