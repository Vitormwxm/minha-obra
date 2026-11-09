import { HttpErrorResponse } from '@angular/common/http';
import { Component, ElementRef, inject, input, OnInit, output, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { AccountsApi, apiMessage } from './accounts-api';
import { parseBrl, parsePercent, validMonth } from './finance-input';

type InccVariant = 'M' | 'DI' | '10';
type IndexStatus = 'FIXED' | 'PENDING' | 'PROJECTED';

interface BuilderProjectionInput {
  installments: { month: string; baseCents: number; indexed: boolean }[];
  incc: { variant: InccVariant; baseMonth: string; intervalMonths: number; lagMonths: number; monthlyRate: string | null } | null;
}

interface BuilderResult {
  rows: {
    month: string;
    baseCents: number;
    projectedCents: number | null;
    remainingCents: number | null;
    paidCents: number | null;
    settled: boolean;
    indexStatus: IndexStatus;
    knownSubtotalCents: number;
    paymentAllocationKnown: boolean;
  }[];
  remainingCents: number | null;
  knownSubtotalCents: number;
}

const currency = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' });

@Component({
  selector: 'app-builder-projection',
  imports: [ReactiveFormsModule],
  templateUrl: './builder-projection.html',
  styleUrl: './builder-projection.css',
})
export class BuilderProjection implements OnInit {
  private readonly accounts = inject(AccountsApi);
  private readonly forms = inject(FormBuilder).nonNullable;
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  readonly startMonth = input.required<string>();
  readonly deliveryMonth = input.required<string>();
  readonly back = output<void>();
  readonly expired = output<void>();
  readonly processing = output<boolean>();
  readonly busy = signal(false);
  readonly result = signal<BuilderResult | null>(null);
  readonly error = signal('');
  readonly limit = 120;
  readonly installments = this.forms.array([this.installment()]);
  readonly form = this.forms.group({
    installments: this.installments,
    useIncc: false,
    variant: this.forms.control<InccVariant>('M'),
    baseMonth: '',
    interval: '1',
    lag: '0',
    rate: '',
  });
  readonly indexLabels: Record<IndexStatus, string> = {
    FIXED: 'Fixa', PENDING: 'Base sem reajuste', PROJECTED: 'INCC projetado',
  };

  ngOnInit(): void {
    this.installments.at(0).controls.month.setValue(this.startMonth());
  }

  addInstallment(): void {
    if (this.busy() || this.installments.length >= this.limit) return;
    const last = this.installments.at(this.installments.length - 1).getRawValue();
    const month = last.month || this.startMonth();
    let next = '';
    if (validMonth(month) && month < this.deliveryMonth()) {
      const date = new Date(month + '-01T00:00:00Z');
      date.setUTCMonth(date.getUTCMonth() + 1);
      next = date.toISOString().slice(0, 7);
    }
    this.installments.push(this.installment(next, last.base, last.indexed));
    this.invalidate();
    this.focus('builder-month-' + (this.installments.length - 1));
  }

  removeInstallment(index: number): void {
    if (this.busy() || this.installments.length <= 1) return;
    this.installments.removeAt(index);
    this.invalidate();
    this.focus('builder-add');
  }

  invalidate(): void {
    this.result.set(null);
    this.error.set('');
  }

  async simulate(): Promise<void> {
    if (this.busy()) return;
    this.invalidate();
    const input = this.readInput();
    if (!input) return;

    this.busy.set(true);
    this.processing.emit(true);
    try {
      const result = await this.accounts.mutate<BuilderResult>('POST', '/finance/builder-projection', input);
      this.result.set(result);
      this.focus('builder-result');
    } catch (error) {
      if (error instanceof HttpErrorResponse && error.status === 401) {
        this.expired.emit();
      } else {
        await this.accounts.recoverCsrf(error);
        this.error.set(apiMessage(error, 'Não foi possível projetar as parcelas da construtora.'));
      }
    } finally {
      this.busy.set(false);
      this.processing.emit(false);
    }
  }

  amount(cents: number | null): string {
    return cents === null ? '—' : currency.format(cents / 100);
  }

  monthLabel(month: string): string {
    return month.slice(5, 7) + '/' + month.slice(0, 4);
  }

  private installment(month = '', base = '', indexed = false) {
    return this.forms.group({ month, base, indexed });
  }

  private readInput(): BuilderProjectionInput | null {
    const values = this.form.getRawValue();
    const installments: BuilderProjectionInput['installments'] = [];
    const months = new Set<string>();
    for (const [index, row] of values.installments.entries()) {
      const month = row.month.trim() || (index === 0 ? this.startMonth() : '');
      if (!validMonth(month) || month < this.startMonth() || month > this.deliveryMonth()) {
        return this.invalid('Use um mês entre o mês atual e a entrega prevista.', 'builder-month-' + index);
      }
      if (months.has(month)) {
        return this.invalid('Use uma parcela por mês de vencimento.', 'builder-month-' + index);
      }
      const baseCents = parseBrl(row.base);
      if (baseCents === null || baseCents === 0) {
        return this.invalid('Informe um valor base maior que zero.', 'builder-base-' + index);
      }
      months.add(month);
      installments.push({ month, baseCents, indexed: row.indexed });
    }

    if (!values.useIncc || !installments.some((row) => row.indexed)) return { installments, incc: null };
    const baseMonth = values.baseMonth.trim();
    if (!validMonth(baseMonth) || installments.some((row) => row.indexed && row.month < baseMonth)) {
      return this.invalid('Use um mês-base igual ou anterior às parcelas indexadas.', 'builder-incc-base');
    }
    const intervalMonths = Number(values.interval);
    const lagMonths = Number(values.lag);
    if (!/^\d+$/.test(values.interval) || intervalMonths < 1 || intervalMonths > 120) {
      return this.invalid('Informe a periodicidade de 1 a 120 meses.', 'builder-incc-interval');
    }
    if (!/^\d+$/.test(values.lag) || lagMonths > 12) {
      return this.invalid('Informe a defasagem de 0 a 12 meses.', 'builder-incc-lag');
    }
    const rate = values.rate.trim();
    const negative = rate.startsWith('-');
    const magnitude = rate ? parsePercent(negative ? rate.slice(1) : rate) : null;
    if (rate && (magnitude === null || (negative && Number(magnitude) >= 100))) {
      return this.invalid('Informe uma hipótese de INCC entre −100% (exclusivo) e 100%.', 'builder-incc-rate');
    }
    const monthlyRate = magnitude === null ? null : (negative ? '-' : '') + magnitude;
    return { installments, incc: { variant: values.variant, baseMonth, intervalMonths, lagMonths, monthlyRate } };
  }

  private invalid(message: string, id: string): null {
    this.error.set(message);
    this.focus(id);
    return null;
  }

  private focus(id: string): void {
    setTimeout(() => this.host.nativeElement.querySelector<HTMLElement>('#' + id)?.focus(), 0);
  }
}
