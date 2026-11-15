import { Component, computed, input } from '@angular/core';
import type { ProjectionRow } from './finance-api';

const currency = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' });

@Component({
  selector: 'app-projection-chart',
  templateUrl: './projection-chart.html',
  styleUrl: './projection-chart.css',
})
export class ProjectionChart {
  readonly rows = input.required<readonly ProjectionRow[]>();

  readonly bars = computed(() => {
    const values = this.rows().map((row) => {
      const charges = [row.trCents, row.interestCents, row.insuranceCents, row.feesCents];
      const hasEstimate = row.knownSubtotalCents > 0 || charges.some((charge) => charge !== null);
      const cents = row.grossCents ?? (row.grossKnownSubtotalCents > 0 || hasEstimate ? row.grossKnownSubtotalCents : null);
      return { month: row.month, cents, minimum: row.grossCents === null };
    });
    const max = values.reduce((largest, { cents }) => Math.max(largest, cents ?? 0), 1);

    return values.map(({ month, cents, minimum }) => ({
      month,
      label: month.slice(5) + '/' + month.slice(0, 4),
      amount: cents === null ? null : currency.format(cents / 100),
      height: ((cents ?? 0) / max) * 100,
      minimum,
    }));
  });
}
