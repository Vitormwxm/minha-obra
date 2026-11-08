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
      const cents = row.totalCents ?? (hasEstimate ? row.knownSubtotalCents : null);
      return { month: row.month, cents, minimum: row.totalCents === null, settled: row.settled };
    });
    const max = values.reduce((largest, { cents }) => Math.max(largest, cents ?? 0), 1);

    return values.map(({ month, cents, minimum, settled }) => ({
      month,
      label: month.slice(5) + '/' + month.slice(0, 4),
      amount: cents === null ? null : currency.format(cents / 100),
      height: ((cents ?? 0) / max) * 100,
      minimum,
      settled,
    }));
  });
}
