import { jsPDF } from 'jspdf';
import { builderPaymentBreakdown, paymentHistorySeries } from './builder-payment';
import type { LedgerMonth } from './finance-api';

const currency = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' });
const caixaColor = '#087f72';
const builderColor = '#d69b40';

export function createPaymentHistoryPdf(rows: readonly LedgerMonth[], totals: { caixa: number; builder: number }): jsPDF {
  const doc = new jsPDF({ orientation: 'landscape', unit: 'pt', format: 'a4', compress: true, putOnlyUsedFonts: true });
  const width = doc.internal.pageSize.getWidth();
  const height = doc.internal.pageSize.getHeight();
  const margin = 36;
  const contentWidth = width - 2 * margin;
  const rowHeight = 24;
  const maximum = rows.reduce((max, row) => Math.max(max, (row.caixaPaidCents ?? 0) + (row.builderPaidCents ?? 0)), 1);
  const chartLeft = margin + 75;
  const chartWidth = 160;
  const right = width - margin - 10;
  const seriesCell = (index: number) => right - 84 * (paymentHistorySeries.length - index);
  doc.setProperties({ title: 'Histórico de pagamentos da obra', subject: 'Pagamentos registrados à CAIXA e à construtora', author: 'Minha Obra' });

  function text(value: string, x: number, y: number, size: number, bold = false, align: 'left' | 'right' = 'left') {
    doc.setFont('helvetica', bold ? 'bold' : 'normal');
    doc.setFontSize(size);
    doc.text(value, x, y, { align });
  }

  function header() {
    doc.setTextColor(caixaColor);
    text('MINHA OBRA', margin, 42, 11, true);
    doc.setTextColor('#242726');
    text('Histórico de pagamentos', margin, 69, 23, true);
    doc.setTextColor('#485c53');
    text('Pagamentos à CAIXA e à construtora, por mês de vencimento', margin, 89, 11);
  }

  function summary() {
    const total = totals.caixa + totals.builder;
    const items = [
      ['Pago à CAIXA', money(totals.caixa)],
      ['Pago à construtora', money(totals.builder)],
      ['Total registrado', money(total)],
    ];
    const cardWidth = (contentWidth - 16) / 3;
    items.forEach(([label, value], index) => {
      const x = margin + index * (cardWidth + 8);
      doc.setFillColor(index === 2 ? '#e0eee5' : '#f3f7f2');
      doc.roundedRect(x, 107, cardWidth, 42, 6, 6, 'F');
      doc.setTextColor('#485c53');
      text(label, x + 10, 122, 9);
      doc.setTextColor('#242726');
      text(value, x + 10, 139, 12, true);
    });
    if (total === 0) return;

    const share = totals.caixa / total;
    const centerX = margin + 28;
    const centerY = 187;
    const radius = 21;
    doc.setFillColor(share === 1 ? caixaColor : builderColor);
    doc.circle(centerX, centerY, radius, 'F');
    if (share > 0 && share < 1) {
      const segments = Math.ceil(share * 80);
      const path: number[][] = [];
      let lastX = 0;
      let lastY = 0;
      for (let i = 0; i <= segments; i++) {
        const angle = i / segments * share * 2 * Math.PI - Math.PI / 2;
        const x = Math.cos(angle) * radius;
        const y = Math.sin(angle) * radius;
        path.push([x - lastX, y - lastY]);
        lastX = x;
        lastY = y;
      }
      doc.setFillColor(caixaColor);
      doc.lines(path, centerX, centerY, [1, 1], 'F', true);
    }
    const percent = (part: number) => part.toLocaleString('pt-BR', { style: 'percent', maximumFractionDigits: 1 });
    text('Distribuição dos pagamentos', margin + 66, 178, 11, true);
    text('CAIXA  ' + percent(share) + '     Construtora  ' + percent(1 - share), margin + 66, 195, 10);
  }

  function tableHeading(tableTop: number) {
    doc.setFillColor('#e8f2ed');
    doc.rect(margin, tableTop, contentWidth, rowHeight, 'F');
    text('Mês', margin + 10, tableTop + 16, 9, true);
    text('Gráfico mensal', chartLeft, tableTop + 16, 9, true);
    paymentHistorySeries.forEach((item, index) => text(item.label, seriesCell(index), tableTop + 16, 8, true, 'right'));
    text('Total registrado', right, tableTop + 16, 9, true, 'right');
  }

  header();
  summary();
  tableHeading(226);
  let y = 226 + rowHeight;
  rows.forEach((row, index) => {
    if (y + rowHeight > height - 70) {
      doc.addPage();
      header();
      tableHeading(112);
      y = 112 + rowHeight;
    }
    if (index % 2 === 0) {
      doc.setFillColor('#f5f6f4');
      doc.rect(margin, y, contentWidth, rowHeight, 'F');
    }
    const amounts = { CAIXA: row.caixaPaidCents, ...builderPaymentBreakdown(row.payments) };
    doc.setFillColor('#e8ece9');
    doc.rect(chartLeft, y + 8, chartWidth, 8, 'F');
    let barX = chartLeft;
    for (const { key, color } of paymentHistorySeries) {
      const size = chartWidth * (amounts[key] ?? 0) / maximum;
      if (size > 0) {
        doc.setFillColor(color);
        doc.rect(barX, y + 8, size, 8, 'F');
        barX += size;
      }
    }
    doc.setTextColor('#242726');
    text(row.month.slice(5) + '/' + row.month.slice(0, 4), margin + 10, y + 16, 9);
    paymentHistorySeries.forEach(({ key }, index) => {
      const cents = key === 'CAIXA' ? amounts.CAIXA : amounts[key] || null;
      text(money(cents), seriesCell(index), y + 16, 8.5, false, 'right');
    });
    text(money((row.caixaPaidCents ?? 0) + (row.builderPaidCents ?? 0)), right, y + 16, 9, true, 'right');
    y += rowHeight;
  });

  const pages = doc.getNumberOfPages();
  for (let page = 1; page <= pages; page++) {
    doc.setPage(page);
    doc.setDrawColor('#dce4dd');
    doc.line(margin, height - 49, width - margin, height - 49);
    doc.setTextColor('#485c53');
    paymentHistorySeries.forEach(({ color, label }, index) => {
      const x = margin + index * 88;
      doc.setFillColor(color);
      doc.rect(x, height - 32, 8, 8, 'F');
      text(label, x + 12, height - 25, 8);
    });
    text('- indica ausência de valor registrado', margin + 455, height - 25, 8);
    text(page + ' / ' + pages, width - margin, height - 25, 8, false, 'right');
  }
  return doc;
}

function money(cents: number | null): string {
  return cents === null ? '-' : currency.format(cents / 100).replace(/\s/g, ' ');
}
