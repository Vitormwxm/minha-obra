import { jsPDF } from 'jspdf';

export interface PaymentHistoryRow {
  month: string;
  caixa: number | null;
  builder: number | null;
}

export type PaymentFilter = 'all' | 'caixa' | 'builder';

const currency = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' });
const caixaColor = '#087f72';
const builderColor = '#d69b40';

export function createPaymentHistoryPdf(rows: readonly PaymentHistoryRow[], filter: PaymentFilter): jsPDF {
  const doc = new jsPDF({ unit: 'pt', format: 'a4', compress: true, putOnlyUsedFonts: true });
  const width = doc.internal.pageSize.getWidth();
  const height = doc.internal.pageSize.getHeight();
  const margin = 36;
  const contentWidth = width - 2 * margin;
  const tableTop = 112;
  const rowHeight = 24;
  const maximum = rows.reduce((max, row) => Math.max(max, (row.caixa ?? 0) + (row.builder ?? 0)), 1);
  const chartLeft = margin + 62;
  const chartWidth = 145;
  const right = width - margin - 10;
  const filterLabel = { all: 'CAIXA e construtora', caixa: 'CAIXA', builder: 'Construtora' }[filter];

  doc.setProperties({ title: 'Histórico de pagamentos da obra', subject: filterLabel, author: 'Minha Obra' });

  function text(value: string, x: number, y: number, size: number, bold = false, align: 'left' | 'right' = 'left') {
    doc.setFont('helvetica', bold ? 'bold' : 'normal');
    doc.setFontSize(size);
    doc.text(value, x, y, { align });
  }

  function header() {
    doc.setTextColor(caixaColor);
    text('Minha Obra', margin, 42, 11, true);
    doc.setTextColor('#242726');
    text('Histórico de pagamentos', margin, 69, 23, true);
    doc.setTextColor('#485c53');
    text(filterLabel + ' | Pagamentos registrados por mês de vencimento', margin, 89, 10);

    doc.setFillColor('#e8f2ed');
    doc.rect(margin, tableTop, contentWidth, rowHeight, 'F');
    text('Mês', margin + 10, tableTop + 16, 9, true);
    text('Gráfico mensal', chartLeft, tableTop + 16, 9, true);
    if (filter === 'all') {
      text('CAIXA', right - 190, tableTop + 16, 9, true, 'right');
      text('Construtora', right - 95, tableTop + 16, 9, true, 'right');
      text('Total registrado', right, tableTop + 16, 9, true, 'right');
    } else {
      text('Valor pago', right, tableTop + 16, 9, true, 'right');
    }
  }

  header();
  let y = tableTop + rowHeight;
  rows.forEach((row, index) => {
    if (y + rowHeight > height - 70) {
      doc.addPage();
      header();
      y = tableTop + rowHeight;
    }
    if (index % 2 === 0) {
      doc.setFillColor('#f5f6f4');
      doc.rect(margin, y, contentWidth, rowHeight, 'F');
    }
    const caixaWidth = chartWidth * (row.caixa ?? 0) / maximum;
    const builderWidth = chartWidth * (row.builder ?? 0) / maximum;
    doc.setFillColor('#e8ece9');
    doc.rect(chartLeft, y + 8, chartWidth, 8, 'F');
    if (caixaWidth > 0) {
      doc.setFillColor(caixaColor);
      doc.rect(chartLeft, y + 8, caixaWidth, 8, 'F');
    }
    if (builderWidth > 0) {
      doc.setFillColor(builderColor);
      doc.rect(chartLeft + caixaWidth, y + 8, builderWidth, 8, 'F');
    }
    doc.setTextColor('#242726');
    text(row.month.slice(5) + '/' + row.month.slice(0, 4), margin + 10, y + 16, 9);
    if (filter === 'all') {
      text(money(row.caixa), right - 190, y + 16, 9, false, 'right');
      text(money(row.builder), right - 95, y + 16, 9, false, 'right');
    }
    text(money((row.caixa ?? 0) + (row.builder ?? 0)), right, y + 16, 9, true, 'right');
    y += rowHeight;
  });

  const pages = doc.getNumberOfPages();
  for (let page = 1; page <= pages; page++) {
    doc.setPage(page);
    doc.setDrawColor('#dce4dd');
    doc.line(margin, height - 49, width - margin, height - 49);
    doc.setTextColor('#485c53');
    let legendX = margin;
    if (filter !== 'builder') {
      doc.setFillColor(caixaColor);
      doc.rect(legendX, height - 32, 8, 8, 'F');
      text('CAIXA', legendX + 13, height - 25, 8);
      legendX += 60;
    }
    if (filter !== 'caixa') {
      doc.setFillColor(builderColor);
      doc.rect(legendX, height - 32, 8, 8, 'F');
      text('Construtora', legendX + 13, height - 25, 8);
    }
    text('- indica ausência de valor registrado', margin + 160, height - 25, 8);
    text(page + ' / ' + pages, width - margin, height - 25, 8, false, 'right');
  }
  return doc;
}

function money(cents: number | null): string {
  return cents === null ? '-' : currency.format(cents / 100).replace(/\s/g, ' ');
}
