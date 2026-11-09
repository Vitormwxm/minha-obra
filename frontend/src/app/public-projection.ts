import { HttpClient } from '@angular/common/http';
import { Component, ElementRef, inject, output, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { apiMessage } from './accounts-api';
import type { FinanceProjection } from './finance-api';
import { parseBrl, parsePercent, validMonth } from './finance-input';
import { ProjectionChart } from './projection-chart';

const currency = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' });
const monthFormatter = new Intl.DateTimeFormat('pt-BR', { month: 'long', year: 'numeric', timeZone: 'UTC' });

function currentMonth(): string {
  const parts = new Intl.DateTimeFormat('en', {
    year: 'numeric', month: '2-digit', timeZone: 'America/Fortaleza',
  }).formatToParts(new Date());
  return parts.find((part) => part.type === 'year')!.value + '-' +
    parts.find((part) => part.type === 'month')!.value;
}

function monthNumber(month: string): number {
  const [year, number] = month.split('-').map(Number);
  return year! * 12 + number!;
}

@Component({
  selector: 'app-public-projection',
  imports: [ReactiveFormsModule, ProjectionChart],
  templateUrl: './public-projection.html',
  styleUrl: './public-projection.css',
})
export class PublicProjection {
  private readonly forms = inject(FormBuilder).nonNullable;
  private readonly http = inject(HttpClient);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  readonly back = output<void>();
  readonly busy = signal(false);
  readonly error = signal('');
  readonly result = signal<FinanceProjection | null>(null);
  readonly form = this.forms.group({
    released: '',
    financed: '',
    annualNominalRate: '',
    monthlyTr: '',
    insurance: '',
    fees: '',
    startMonth: currentMonth(),
    endMonth: '',
  });

  async simulate(): Promise<void> {
    if (this.busy()) return;
    this.clearResult();
    const values = this.form.getRawValue();
    const releasedCents = parseBrl(values.released);
    const financedCents = parseBrl(values.financed);
    const annualNominalRate = parsePercent(values.annualNominalRate);
    const monthlyTr = parsePercent(values.monthlyTr);
    const insuranceCents = parseBrl(values.insurance);
    const feesCents = parseBrl(values.fees);

    if (releasedCents === null || financedCents === null || financedCents === 0 || releasedCents > financedCents) {
      this.showError('Confira o saldo liberado e o valor financiado. O saldo pode ser zero e deve caber no financiamento.');
      return;
    }
    if (annualNominalRate === null || (values.monthlyTr.trim() && monthlyTr === null)) {
      this.showError('Informe juros nominais e TR de 0% a 100%, com até seis casas decimais.');
      return;
    }
    if ((values.insurance.trim() && insuranceCents === null) || (values.fees.trim() && feesCents === null)) {
      this.showError('Confira o seguro e as taxas mensais. Use valores em reais com até duas casas decimais.');
      return;
    }
    if (!validMonth(values.startMonth) || !validMonth(values.endMonth)) {
      this.showError('Informe o primeiro mês e o mês previsto de entrega.');
      return;
    }
    const months = monthNumber(values.endMonth) - monthNumber(values.startMonth) + 1;
    if (months < 1 || months > 120) {
      this.showError('Informe um período de 1 a 120 meses.');
      return;
    }

    this.busy.set(true);
    try {
      const result = await firstValueFrom(this.http.post<FinanceProjection>('/api/public/projection', {
        releasedCents,
        financedCents,
        annualNominalRate,
        monthlyTr,
        insuranceCents,
        feesCents,
        startMonth: values.startMonth,
        endMonth: values.endMonth,
      }, { credentials: 'omit' }));
      this.result.set(result);
      this.focus('#public-projection-result');
    } catch (error) {
      this.showError(apiMessage(error, 'Não foi possível calcular a projeção. Tente novamente.'));
    } finally {
      this.busy.set(false);
    }
  }

  clearResult(): void {
    this.error.set('');
    this.result.set(null);
  }

  amount(cents: number | null): string {
    return cents === null ? 'Em aberto' : currency.format(cents / 100);
  }

  month(value: string): string {
    return monthFormatter.format(new Date(value + '-01T00:00:00Z'));
  }

  private showError(message: string): void {
    this.error.set(message);
    this.focus('#public-projection-error');
  }

  private focus(selector: string): void {
    setTimeout(() => this.host.nativeElement.querySelector<HTMLElement>(selector)?.focus(), 0);
  }
}
