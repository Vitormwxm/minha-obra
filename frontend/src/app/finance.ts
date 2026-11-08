import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, ElementRef, inject, OnInit, output, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { AccountsApi, apiMessage } from './accounts-api';
import {
  FinanceApi, FinanceSnapshot, FinancingDraft, LedgerMonth, MonthDraft, Payment, PaymentKind, PaymentStatus,
} from './finance-api';
import { parseBrl, parseNominalRate, validDate, validMonth } from './finance-input';
import { OperationKey } from './security-input';

type FinanceView = 'summary' | 'financing' | 'month';

const currency = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' });
const moneyInput = new Intl.NumberFormat('pt-BR', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const monthName = new Intl.DateTimeFormat('pt-BR', { year: 'numeric', month: 'long', timeZone: 'UTC' });

class InputProblem extends Error {
  constructor(readonly field: string, message: string) { super(message); }
}

@Component({
  selector: 'app-finance',
  imports: [ReactiveFormsModule],
  templateUrl: './finance.html',
  styleUrl: './finance.css',
})
export class Finance implements OnInit {
  private readonly api = inject(FinanceApi);
  private readonly accounts = inject(AccountsApi);
  private readonly forms = inject(FormBuilder).nonNullable;
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly destroy = inject(DestroyRef);
  private readonly financingKey = new OperationKey();
  private readonly monthKey = new OperationKey();
  private readonly deleteKey = new OperationKey();

  readonly expired = output<void>();
  readonly processing = output<boolean>();
  readonly view = signal<FinanceView>('summary');
  readonly snapshot = signal<FinanceSnapshot | null>(null);
  readonly loading = signal(false);
  readonly busy = signal(false);
  readonly loadError = signal('');
  readonly error = signal('');
  readonly notice = signal('');
  readonly invalidField = signal('');
  readonly editingMonth = signal<LedgerMonth | null>(null);
  readonly confirmingDelete = signal(false);
  readonly today = this.dateToday();
  readonly paymentLimit = 100;
  readonly statuses: Record<PaymentStatus, string> = {
    OPEN: 'Em aberto', PAID: 'Quitado', NO_PAYMENT: 'Sem pagamento',
  };

  readonly financingForm = this.forms.group({
    financed: '', released: '', nominal: '', delivery: '', tracking: '', subsidy: '',
  });
  readonly payments = this.forms.array<ReturnType<Finance['paymentForm']>>([]);
  readonly monthForm = this.forms.group({
    month: '', caixaDue: '', builderDue: '',
    caixaStatus: this.forms.control<PaymentStatus>('OPEN'),
    builderStatus: this.forms.control<PaymentStatus>('OPEN'),
    released: '', insurance: '', fees: '', payments: this.payments,
  });

  ngOnInit(): void { void this.reload(); }

  async reload(): Promise<void> {
    if (this.loading()) return;
    this.loading.set(true);
    this.loadError.set('');
    try {
      this.snapshot.set(await this.api.get());
    } catch (error) {
      if (!await this.sessionEnded(error)) {
        this.loadError.set(apiMessage(error, 'Não foi possível carregar sua obra.'));
      }
    } finally {
      this.loading.set(false);
    }
  }

  openFinancing(): void {
    const financing = this.snapshot()?.financing;
    this.financingForm.reset(financing ? {
      financed: this.inputAmount(financing.financedCents), released: this.inputAmount(financing.releasedCents),
      nominal: financing.annualNominalRate.replace('.', ','), delivery: financing.expectedDeliveryDate,
      tracking: financing.trackingStartMonth, subsidy: this.inputAmount(financing.subsidyCents),
    } : { tracking: this.today.slice(0, 7) });
    this.show('financing');
  }

  openMonth(existing: LedgerMonth | null = null): void {
    const snapshot = this.snapshot();
    if (!snapshot?.financing) return;
    this.editingMonth.set(existing);
    this.payments.clear();
    this.monthForm.reset({
      month: existing?.month ?? snapshot.missingMonths[0] ?? this.nextMonth(snapshot),
      caixaDue: existing?.caixaDueDate ?? '', builderDue: existing?.builderDueDate ?? '',
      caixaStatus: existing?.caixaStatus ?? 'OPEN', builderStatus: existing?.builderStatus ?? 'OPEN',
      released: this.inputAmount(existing?.releasedCents), insurance: this.inputAmount(existing?.insuranceCents),
      fees: this.inputAmount(existing?.feesCents),
    });
    existing?.payments.forEach((payment) => this.payments.push(this.paymentForm(payment)));
    this.show('month');
  }

  backToSummary(): void {
    if (!this.busy()) this.show('summary');
  }

  addPayment(kind: PaymentKind): void {
    if (this.busy() || this.payments.length >= this.paymentLimit) return;
    this.payments.push(this.paymentForm(undefined, kind));
    this.focus('payment-date-' + (this.payments.length - 1));
  }

  removePayment(index: number): void {
    if (this.busy()) return;
    this.payments.removeAt(index);
    this.clearInputProblem();
    this.focus('add-caixa-payment');
  }

  setDeleteConfirmation(value: boolean): void {
    if (this.busy()) return;
    this.confirmingDelete.set(value);
    this.clearInputProblem();
    this.error.set('');
    this.focus(value ? 'confirm-delete-month' : 'delete-month');
  }

  async saveFinancing(): Promise<void> {
    await this.mutate('Financiamento salvo.', async () => {
      const values = this.financingForm.getRawValue();
      const financed = this.requiredMoney(values.financed, 'financed', 'valor do financiamento');
      const released = this.requiredMoney(values.released, 'released', 'saldo atual liberado', true);
      if (released > financed) throw new InputProblem('released', 'O saldo liberado deve ser igual ou menor que o valor do financiamento.');
      const nominal = parseNominalRate(values.nominal);
      if (nominal === null) throw new InputProblem('nominal', 'Informe juros nominais de 0 a 100% ao ano, com até seis casas decimais.');
      const tracking = values.tracking.trim();
      if (!validMonth(tracking)) throw new InputProblem('tracking', 'Informe o início do acompanhamento no formato AAAA-MM.');
      const delivery = this.date(values.delivery, 'delivery', 'previsão de entrega');
      if (delivery.slice(0, 7) < tracking) throw new InputProblem('delivery', 'A entrega deve ocorrer no início do acompanhamento ou depois dele.');
      const snapshot = this.snapshot();
      if (snapshot?.months.some((month) => month.month < tracking)) {
        throw new InputProblem('tracking', 'O início deve incluir todos os meses já lançados.');
      }
      if (snapshot?.months.some((month) => (month.releasedCents ?? 0) > financed)) {
        throw new InputProblem('financed', 'O valor do financiamento deve cobrir os saldos liberados já lançados.');
      }
      const payload: FinancingDraft = {
        version: snapshot?.financing?.version ?? null, financedCents: financed, releasedCents: released,
        annualNominalRate: nominal, expectedDeliveryDate: delivery, trackingStartMonth: tracking,
        subsidyCents: this.optionalMoney(values.subsidy, 'subsidy', 'subsídio'),
      };
      await this.api.saveFinancing(payload, await this.financingKey.forPayload('financing', payload));
      this.financingKey.clear();
    });
  }

  async saveMonth(): Promise<void> {
    const snapshot = this.snapshot();
    if (!snapshot?.financing || this.confirmingDelete()) return;
    const financing = snapshot.financing;
    await this.mutate('Lançamento salvo.', async () => {
      const values = this.monthForm.getRawValue();
      const existing = this.editingMonth();
      const month = existing?.month ?? values.month.trim();
      if (!validMonth(month)) throw new InputProblem('ledger-month', 'Informe o mês de vencimento no formato AAAA-MM.');
      if (month < financing.trackingStartMonth) throw new InputProblem('ledger-month', 'Escolha um mês dentro do período acompanhado.');
      if (!existing && snapshot.months.some((row) => row.month === month)) {
        throw new InputProblem('ledger-month', 'Este mês já foi lançado. Abra os detalhes dele na planilha para editar.');
      }
      const released = this.optionalMoney(values.released, 'month-released', 'saldo liberado no mês');
      if (released !== null && released > financing.financedCents) {
        throw new InputProblem('month-released', 'O saldo liberado no mês deve ser igual ou menor que o valor do financiamento.');
      }
      const payments = values.payments.map((payment, index): Payment => {
        const paidOn = this.date(payment.paidOn, 'payment-date-' + index, 'data do pagamento');
        if (paidOn > this.today) throw new InputProblem('payment-date-' + index, 'A data do pagamento deve ser hoje ou anterior.');
        const paidCents = this.requiredMoney(payment.paid, 'payment-paid-' + index, 'valor pago');
        const lateChargeCents = this.optionalMoney(payment.late, 'payment-late-' + index, 'juros e multa');
        if (lateChargeCents !== null && lateChargeCents > paidCents) {
          throw new InputProblem('payment-late-' + index, 'Juros e multa já incluídos devem caber no valor pago.');
        }
        return { kind: payment.kind, paidOn, paidCents, lateChargeCents };
      });
      this.checkStatus(values.caixaStatus, 'CAIXA', payments, 'caixa-status');
      this.checkStatus(values.builderStatus, 'CONSTRUTORA', payments, 'builder-status');
      const payload: MonthDraft = {
        version: existing?.version ?? null,
        caixaDueDate: this.dueDate(values.caixaDue, month, 'caixa-due'),
        builderDueDate: this.dueDate(values.builderDue, month, 'builder-due'),
        caixaStatus: values.caixaStatus, builderStatus: values.builderStatus,
        releasedCents: released, insuranceCents: this.optionalMoney(values.insurance, 'insurance', 'seguro'),
        feesCents: this.optionalMoney(values.fees, 'fees', 'taxas'), payments,
      };
      await this.api.saveMonth(month, payload, await this.monthKey.forPayload(month, payload));
      this.monthKey.clear();
    });
  }

  async deleteMonth(): Promise<void> {
    const month = this.editingMonth();
    if (!month || !this.confirmingDelete()) return;
    await this.mutate('Lançamento excluído.', async () => {
      const key = await this.deleteKey.forPayload(month.month, month.version);
      await this.api.deleteMonth(month.month, month.version, key);
      this.deleteKey.clear();
    });
  }

  clearInputProblem(): void {
    if (!this.invalidField()) return;
    this.invalidField.set('');
    this.error.set('');
  }

  amount(cents: number | null): string { return cents === null ? '—' : currency.format(cents / 100); }

  monthLabel(month: string): string { return monthName.format(new Date(month + '-01T00:00:00Z')); }

  dateLabel(date: string): string { return date.split('-').reverse().join('/'); }

  private paymentForm(payment?: Payment, kind: PaymentKind = 'CAIXA') {
    return this.forms.group({
      kind: this.forms.control<PaymentKind>(payment?.kind ?? kind), paidOn: payment?.paidOn ?? this.today,
      paid: this.inputAmount(payment?.paidCents), late: this.inputAmount(payment?.lateChargeCents),
    });
  }

  private inputAmount(cents?: number | null): string { return cents == null ? '' : moneyInput.format(cents / 100); }

  private optionalMoney(value: string, field: string, label: string): number | null {
    if (!value.trim()) return null;
    const cents = parseBrl(value);
    if (cents === null) throw new InputProblem(field, `Informe um valor válido para ${label}, de R$ 0 a R$ 100.000.000.`);
    return cents;
  }

  private requiredMoney(value: string, field: string, label: string, allowZero = false): number {
    const cents = this.optionalMoney(value, field, label);
    if (cents === null || (cents === 0 && !allowZero)) {
      throw new InputProblem(field, allowZero ? `Informe ${label}.` : `Informe ${label} maior que zero.`);
    }
    return cents;
  }

  private date(value: string, field: string, label: string): string {
    const date = value.trim();
    if (!validDate(date)) throw new InputProblem(field, `Informe uma ${label} válida no formato AAAA-MM-DD.`);
    return date;
  }

  private dueDate(value: string, month: string, field: string): string | null {
    if (!value.trim()) return null;
    const date = this.date(value, field, 'data de vencimento');
    if (date.slice(0, 7) !== month) throw new InputProblem(field, 'O vencimento deve estar no mês do lançamento.');
    return date;
  }

  private checkStatus(status: PaymentStatus, kind: PaymentKind, payments: Payment[], field: string): void {
    const hasPayment = payments.some((payment) => payment.kind === kind);
    const name = kind === 'CAIXA' ? 'CAIXA' : 'construtora';
    if (status === 'PAID' && !hasPayment) throw new InputProblem(field, `Adicione um pagamento para marcar ${name} como quitado.`);
    if (status === 'NO_PAYMENT' && hasPayment) throw new InputProblem(field, `Altere a situação ou retire os pagamentos da ${name}.`);
  }

  private dateToday(): string {
    const parts = new Intl.DateTimeFormat('en', {
      timeZone: 'America/Fortaleza', year: 'numeric', month: '2-digit', day: '2-digit',
    }).formatToParts(new Date());
    return ['year', 'month', 'day'].map((part) => parts.find((value) => value.type === part)?.value).join('-');
  }

  private nextMonth(snapshot: FinanceSnapshot): string {
    const months = new Set(snapshot.months.map((row) => row.month));
    let month = [this.today.slice(0, 7), snapshot.financing?.trackingStartMonth ?? ''].sort()[1];
    while (months.has(month) && month !== '2100-12') {
      const date = new Date(month + '-01T00:00:00Z');
      date.setUTCMonth(date.getUTCMonth() + 1);
      month = date.toISOString().slice(0, 7);
    }
    return months.has(month) ? '' : month;
  }

  private async mutate(success: string, action: () => Promise<void>): Promise<void> {
    if (this.busy() || this.loading() || this.loadError()) return;
    this.busy.set(true);
    this.processing.emit(true);
    this.error.set('');
    this.notice.set('');
    this.invalidField.set('');
    try {
      await action();
      this.show('summary');
      this.notice.set(success);
      await this.reload();
    } catch (error) {
      if (error instanceof InputProblem) {
        this.invalidField.set(error.field);
        this.error.set(error.message);
        this.focus(error.field);
      } else if (!await this.sessionEnded(error)) {
        if (error instanceof HttpErrorResponse && error.status === 409) {
          this.error.set('Estes dados mudaram desde a última consulta. Volte à planilha e atualize os dados antes de editar novamente.');
        } else {
          this.error.set(apiMessage(error, 'Não foi possível salvar a alteração. Tente novamente.'));
        }
      }
    } finally {
      this.busy.set(false);
      if (!this.destroy.destroyed) this.processing.emit(false);
    }
  }

  private async sessionEnded(error: unknown): Promise<boolean> {
    if (this.destroy.destroyed) return true;
    if (!(error instanceof HttpErrorResponse)) return false;
    if (error.status === 403) {
      await this.accounts.recoverCsrf(error);
      try {
        if ((await this.accounts.me()).stage === 'AUTHENTICATED') return false;
      } catch (sessionError) {
        if (!(sessionError instanceof HttpErrorResponse && sessionError.status === 401)) return false;
      }
    } else if (error.status !== 401) {
      return false;
    }
    if (!this.destroy.destroyed) this.expired.emit();
    return true;
  }

  private show(view: FinanceView): void {
    this.view.set(view);
    this.confirmingDelete.set(false);
    this.invalidField.set('');
    this.error.set('');
    this.notice.set('');
    this.focus('finance-title');
  }

  private focus(id: string): void {
    setTimeout(() => this.host.nativeElement.querySelector<HTMLElement>('#' + id)?.focus(), 0);
  }
}
