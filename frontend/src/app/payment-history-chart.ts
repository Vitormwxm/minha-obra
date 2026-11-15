import { Component, computed, input, signal } from '@angular/core';
import type { LedgerMonth } from './finance-api';
import type { PaymentFilter, PaymentHistoryRow } from './payment-history-pdf';

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
  readonly hasPayments = computed(() => this.months().some(month => month.payments.length > 0));
  readonly downloading = signal(false);
  readonly downloadError = signal('');

  readonly rows = computed<PaymentHistoryRow[]>(() => {
    const showCaixa = this.filter() !== 'builder';
    const showBuilder = this.filter() !== 'caixa';
    return this.months().filter(month => month.payments.length > 0).map(month => ({
      month: month.month,
      caixa: showCaixa ? month.caixaPaidCents : null,
      builder: showBuilder ? month.builderPaidCents : null,
    })).filter(month => month.caixa !== null || month.builder !== null);
  });

  readonly bars = computed(() => {
    const rows = this.rows();
    const max = rows.reduce((largest, month) => Math.max(largest,
      (month.caixa ?? 0) + (month.builder ?? 0)), 1);

    return rows.map(month => {
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
    const rows = this.rows();
    if (this.downloading() || !rows.length) return;

    const filter = this.filter();
    this.downloadError.set('');
    this.downloading.set(true);
    try {
      const { createPaymentHistoryPdf } = await import('./payment-history-pdf');
      const pdf = createPaymentHistoryPdf(rows, filter);
      const android = (window as Window & { MinhaObraPaymentPdf?: { postMessage(data: string): void } }).MinhaObraPaymentPdf;
      if (android) {
        const data = pdf.output('datauristring').split(',')[1];
        android.postMessage(data);
      } else if (navigator.userAgent.includes('MinhaObraAndroid/')) {
        this.downloadError.set('Atualize o Android System WebView para salvar o PDF no aplicativo.');
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
