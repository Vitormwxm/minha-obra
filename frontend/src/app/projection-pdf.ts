import { jsPDF } from 'jspdf';
import type { FinanceProjection, Financing, ProjectionAssumptions, ProjectionRow } from './finance-api';

export interface ProjectionReport extends FinanceProjection {
  financing: Financing;
  assumptions: ProjectionAssumptions;
}

const currency = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' });
const decimal = new Intl.NumberFormat('pt-BR', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const columns = [
  { label: 'Mês', width: 50 },
  { label: 'Liberação', width: 81 },
  { label: 'Saldo liberado', width: 88 },
  { label: 'Juros', width: 65 },
  { label: 'Correção TR', width: 65 },
  { label: 'Seguro', width: 65 },
  { label: 'Taxas', width: 61 },
  { label: 'Pago à CAIXA', width: 85 },
  { label: 'Restante', width: 101 },
  { label: 'Situação', width: 109 },
];

export function createProjectionPdf(report: ProjectionReport, generatedOn: string): jsPDF {
  const doc = new jsPDF({ orientation: 'landscape', unit: 'pt', format: 'a4', compress: true, putOnlyUsedFonts: true });
  const width = doc.internal.pageSize.getWidth();
  const height = doc.internal.pageSize.getHeight();
  const margin = 36;
  const contentWidth = width - margin * 2;
  const rowHeight = 19;
  const tableBottom = height - 52;

  doc.setProperties({
    title: 'Projeção de evolução de obra',
    subject: 'Simulação mensal até a entrega',
    author: 'Minha Obra',
  });

  function text(value: string, x: number, y: number, size: number, bold = false, align: 'left' | 'right' = 'left'): void {
    doc.setFont('helvetica', bold ? 'bold' : 'normal');
    doc.setFontSize(size);
    doc.text(value, x, y, { align });
  }

  function heading(continued: boolean): void {
    doc.setTextColor('#087f72');
    text('MINHA OBRA', margin, 42, 11, true);
    doc.setTextColor('#24382f');
    text('Projeção de evolução de obra', margin, 69, 23, true);
    doc.setTextColor('#4f675b');
    text('Simulação mensal da CAIXA até a entrega prevista', margin, 88, 11);
    text('Gerado em ' + formatDate(generatedOn), width - margin, 42, 9, false, 'right');
    if (continued) text('Detalhamento mensal - continuação', margin, 113, 12, true);
  }

  function infoRow(y: number, items: ReadonlyArray<readonly [string, string]>): void {
    const gap = 8;
    const cardWidth = (contentWidth - gap * (items.length - 1)) / items.length;
    items.forEach(([label, value], index) => {
      const x = margin + index * (cardWidth + gap);
      doc.setFillColor('#f3f7f2');
      doc.roundedRect(x, y, cardWidth, 42, 6, 6, 'F');
      doc.setTextColor('#4f675b');
      text(label, x + 10, y + 14, 9);
      doc.setTextColor('#24382f');
      text(value, x + 10, y + 31, 12, true);
    });
  }

  function tableRow(y: number, values: string[], index: number): void {
    const header = index < 0;
    doc.setFillColor(header ? '#e0eee5' : index % 2 === 0 ? '#ffffff' : '#f6f9f5');
    doc.rect(margin, y, contentWidth, rowHeight, 'F');
    doc.setTextColor(header ? '#305043' : '#24382f');
    let x = margin;
    for (const [columnIndex, column] of columns.entries()) {
      const alignRight = columnIndex > 0 && columnIndex < columns.length - 1;
      const cellWidth = column.width * contentWidth / 770;
      text(values[columnIndex], alignRight ? x + cellWidth - 7 : x + 7, y + 12.5, 8.5, header, alignRight ? 'right' : 'left');
      x += cellWidth;
    }
  }

  heading(false);
  infoRow(107, [
    ['Saldo atual liberado', formatMoney(report.financing.releasedCents)],
    ['Valor final do financiamento', formatMoney(report.financing.financedCents)],
    ['Juros nominais anuais', formatPercent(report.financing.annualNominalRate)],
    ['Previsão de entrega', formatDate(report.financing.expectedDeliveryDate)],
  ]);
  infoRow(157, [
    ['TR projetada ao mês', formatPercent(report.assumptions.monthlyTr)],
    ['Seguro mensal', formatMoney(report.assumptions.insuranceCents)],
    ['Taxas mensais', formatMoney(report.assumptions.feesCents)],
  ]);

  const partial = report.totalCents === null;
  doc.setFillColor('#e0eee5');
  doc.roundedRect(margin, 207, contentWidth, 45, 6, 6, 'F');
  doc.setTextColor('#305043');
  text(partial ? 'Subtotal mínimo estimado' : 'Total restante estimado', margin + 12, 234, 12, true);
  text(formatMoney(report.totalCents ?? report.knownSubtotalCents), width - margin - 12, 235, 16, true, 'right');

  doc.setTextColor('#4f675b');
  text('A projeção distribui as liberações até o mês da entrega. A TR e os juros usam o saldo do início de cada mês.', margin, 270, 9);
  text('Quando os dados são parciais, o PDF mostra o subtotal mínimo estimado. Os valores da tabela estão em reais (R$).', margin, 284, 9);

  if (report.rows.length) {
    let y = 299;
    tableRow(y, columns.map((column) => column.label), -1);
    y += rowHeight;
    report.rows.forEach((row, index) => {
      if (y + rowHeight > tableBottom) {
        doc.addPage();
        heading(true);
        y = 128;
        tableRow(y, columns.map((column) => column.label), -1);
        y += rowHeight;
      }
      tableRow(y, rowValues(row), index);
      y += rowHeight;
    });
  } else {
    text('A entrega prevista é anterior ao mês atual. Nenhum mês a projetar.', margin, 314, 11);
  }

  const pages = doc.getNumberOfPages();
  for (let page = 1; page <= pages; page++) {
    doc.setPage(page);
    doc.setDrawColor('#c6d7cc');
    doc.line(margin, height - 42, width - margin, height - 42);
    doc.setTextColor('#4f675b');
    text('Minha Obra - Simulação CAIXA | -: valor indisponível | Mín.: subtotal mínimo', margin, height - 27, 8);
    text(page + ' / ' + pages, width - margin, height - 27, 8, false, 'right');
  }

  return doc;
}

function formatMoney(cents: number | null): string {
  return cents === null ? 'Em aberto' : currency.format(cents / 100).replace(/\s/g, ' ');
}

function formatPercent(rate: string | null): string {
  return rate === null ? 'Em aberto' : rate.replace('.', ',') + '%';
}

function formatDate(date: string): string {
  return date.split('-').reverse().join('/');
}

function rowValues(row: ProjectionRow): string[] {
  const money = (cents: number | null) => cents === null ? '-' : decimal.format(cents / 100);
  const status = row.settled ? 'Encerrado' : row.paymentAllocationKnown ? 'Projetado' : 'Encargos a conferir';
  return [
    formatDate(row.month), money(row.releaseCents), money(row.releasedCents), money(row.interestCents),
    money(row.trCents), money(row.insuranceCents), money(row.feesCents), money(row.caixaPaidCents),
    row.totalCents === null ? 'Mín. ' + money(row.knownSubtotalCents) : money(row.totalCents),
    status,
  ];
}
