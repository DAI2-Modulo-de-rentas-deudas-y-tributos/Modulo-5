package ar.gob.municipalidad.rentas;

import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
class PdfDocumentService {
    byte[] bill(ApiDtos.BillDetail detail) {
        // 32 deudas por página dejan lugar al encabezado, total y pie dentro de A4.
        List<BillDebt> debts = detail.debts().stream().sorted(Comparator.comparing(d -> d.debtId)).toList();
        int pageCount = Math.max(1, (debts.size() + 31) / 32);
        List<String> objects = new ArrayList<>(List.of(
            "<< /Type /Catalog /Pages 2 0 R >>", "",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"));
        StringBuilder kids = new StringBuilder();
        for (int page = 0; page < pageCount; page++) {
            List<String> lines = new ArrayList<>(List.of(
                "BOLETA " + detail.bill().number,
                "Contribuyente: " + detail.bill().taxpayerId,
                "Emision: " + detail.bill().issueDate + "  Vencimiento: " + detail.bill().dueDate));
            if (detail.bill().status == BillStatus.CANCELLED) {
                if (BillPresentationStatusService.PAID.equals(detail.presentationStatus())) {
                    lines.add("ESTADO: PAGADA");
                } else if (BillPresentationStatusService.PARTIAL_PAYMENT.equals(detail.presentationStatus())) {
                    lines.add("ESTADO: PAGO PARCIAL - BOLETA SIN VIGENCIA");
                    lines.add("Motivo: Anulada por cambio en el saldo de una o mas deudas.");
                } else if (BillPresentationStatusService.PAYMENT_REVERSED.equals(detail.presentationStatus())) {
                    lines.add("ESTADO: PAGO REVERTIDO - BOLETA SIN VIGENCIA");
                } else {
                    lines.add("ESTADO: ANULADA");
                    if (detail.bill().cancellationReason != null) lines.add("Motivo: Anulada por cambio en el saldo de una o mas deudas.");
                }
            }
            for (BillDebt debt : debts.subList(page * 32, Math.min(debts.size(), (page + 1) * 32))) {
                lines.add("Deuda " + debt.debtId + ": $ " + debt.amountAtIssue);
            }
            if (page == pageCount - 1) lines.add("TOTAL: $ " + detail.bill().totalAmount);
            StringBuilder text = new StringBuilder();
            for (int line = 0; line < lines.size(); line++) {
                text.append(pdfLine(lines.get(line), 790 - line * 20));
            }
            text.append(pdfLine("Pagina " + (page + 1) + " de " + pageCount, 50));
            int pageId = objects.size() + 1;
            kids.append(pageId).append(" 0 R ");
            objects.add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 3 0 R >> >> /Contents "
                + (pageId + 1) + " 0 R >>");
            objects.add("<< /Length " + text.toString().getBytes(StandardCharsets.ISO_8859_1).length
                + " >>\nstream\n" + text + "endstream");
        }
        objects.set(1, "<< /Type /Pages /Kids [" + kids + "] /Count " + pageCount + " >>");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<Integer> offsets = new ArrayList<>();
        write(out, "%PDF-1.4\n");
        for (int i = 0; i < objects.size(); i++) {
            offsets.add(out.size());
            write(out, (i + 1) + " 0 obj\n" + objects.get(i) + "\nendobj\n");
        }
        int xref = out.size();
        write(out, "xref\n0 " + (objects.size() + 1) + "\n0000000000 65535 f \n");
        for (int offset : offsets) write(out, String.format(Locale.ROOT, "%010d 00000 n \n", offset));
        write(out, "trailer << /Size " + (objects.size() + 1) + " /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF");
        return out.toByteArray();
    }
    private String pdfLine(String value, int y) {
        return "BT /F1 12 Tf 1 0 0 1 50 " + y + " Tm (" + escape(value) + ") Tj ET\n";
    }
    private String escape(String value){return value.replaceAll("[^\\x20-\\x7E]","?").replace("\\","\\\\").replace("(","\\(").replace(")","\\)");}
    private void write(ByteArrayOutputStream out,String value){out.writeBytes(value.getBytes(StandardCharsets.ISO_8859_1));}
}
