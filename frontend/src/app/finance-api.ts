import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { AccountsApi } from './accounts-api';

export type PaymentKind = 'CAIXA' | 'CONSTRUTORA';
export type PaymentStatus = 'OPEN' | 'PAID' | 'NO_PAYMENT';

export interface FinancingDraft {
  version: number | null;
  financedCents: number;
  releasedCents: number;
  annualNominalRate: string;
  expectedDeliveryDate: string;
  trackingStartMonth: string;
  subsidyCents: number | null;
}

export interface Financing extends FinancingDraft {
  id: string;
  version: number;
}

export interface Payment {
  kind: PaymentKind;
  paidOn: string;
  paidCents: number;
  lateChargeCents: number | null;
}

export interface MonthDraft {
  version: number | null;
  caixaDueDate: string | null;
  builderDueDate: string | null;
  caixaStatus: PaymentStatus;
  builderStatus: PaymentStatus;
  releasedCents: number | null;
  insuranceCents: number | null;
  feesCents: number | null;
  payments: Payment[];
}

export interface LedgerMonth extends MonthDraft {
  version: number;
  month: string;
  closed: boolean;
  caixaPaidCents: number | null;
  builderPaidCents: number | null;
  monthlyTotalCents: number | null;
  caixaAccumulatedCents: number;
  builderAccumulatedCents: number;
  accumulatedPaidCents: number;
  monthlyVariationCents: number | null;
}

export interface FinanceSnapshot {
  financing: Financing | null;
  months: LedgerMonth[];
  missingMonths: string[];
}

@Injectable({ providedIn: 'root' })
export class FinanceApi {
  private readonly http = inject(HttpClient);
  private readonly accounts = inject(AccountsApi);

  get(): Promise<FinanceSnapshot> {
    return firstValueFrom(this.http.get<FinanceSnapshot>('/api/finance', { withCredentials: true }));
  }

  saveFinancing(payload: FinancingDraft, key: string): Promise<{ version: number }> {
    return this.accounts.mutate('PUT', '/finance/financing', payload, key);
  }

  saveMonth(month: string, payload: MonthDraft, key: string): Promise<{ version: number }> {
    return this.accounts.mutate('PUT', '/finance/months/' + encodeURIComponent(month), payload, key);
  }

  deleteMonth(month: string, version: number, key: string): Promise<void> {
    return this.accounts.mutate('DELETE', '/finance/months/' + encodeURIComponent(month), { version }, key);
  }
}
