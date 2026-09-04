package ar.gob.municipalidad.rentas;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.io.ByteArrayOutputStream;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

@Service
class BillingService {
    private final BillRepository bills; private final BillDebtRepository billDebts; private final DebtRepository debts; private final TaxpayerRepository taxpayers; private final CurrentIdentity identity;
    BillingService(BillRepository bills,BillDebtRepository billDebts,DebtRepository debts,TaxpayerRepository taxpayers,CurrentIdentity identity){this.bills=bills;this.billDebts=billDebts;this.debts=debts;this.taxpayers=taxpayers;this.identity=identity;}
    @Transactional Bill create(ApiDtos.CreateBillRequest request){taxpayers.findById(request.taxpayerId()).orElseThrow(()->CatalogService.notFound("Contribuyente"));CatalogService.require(!request.dueDate().isBefore(LocalDate.now()),"INVALID_BILL_DUE_DATE","El vencimiento no puede estar en el pasado");List<Long> unique=request.debtIds().stream().distinct().toList();CatalogService.require(unique.size()==request.debtIds().size(),"DUPLICATE_BILL_DEBT","La boleta contiene deudas repetidas");List<Debt> selected=unique.stream().map(id->debts.findById(id).orElseThrow(()->CatalogService.notFound("Deuda"))).toList();CatalogService.require(selected.stream().allMatch(x->x.taxpayerId.equals(request.taxpayerId())),"BILL_TAXPAYER_MISMATCH","Todas las deudas deben pertenecer al contribuyente");CatalogService.require(selected.stream().allMatch(x->x.status!=DebtStatus.PAID&&x.status!=DebtStatus.CANCELLED&&x.outstandingBalance.signum()>0),"DEBT_NOT_BILLABLE","La boleta contiene una deuda no exigible");Bill bill=new Bill();bill.number="BILL-"+UUID.randomUUID();bill.taxpayerId=request.taxpayerId();bill.totalAmount=selected.stream().map(x->x.outstandingBalance).reduce(BigDecimal.ZERO,BigDecimal::add).setScale(2,RoundingMode.HALF_UP);bill.issueDate=LocalDate.now();bill.dueDate=request.dueDate();bill.status=BillStatus.ISSUED;bill.createdBy=identity.get().userId();bill.createdAt=OffsetDateTime.now();bills.save(bill);for(Debt debt:selected){BillDebt link=new BillDebt();link.billId=bill.id;link.debtId=debt.id;link.amountAtIssue=debt.outstandingBalance;billDebts.save(link);}return bill;}
    ApiDtos.BillDetail detail(Long id){Bill bill=bills.findById(id).orElseThrow(()->CatalogService.notFound("Boleta"));identity.requireOwnership(bill.taxpayerId);return new ApiDtos.BillDetail(bill,billDebts.findByBillId(id));}
}

@Service
class ElectronicPaymentService {
    private final DebtRepository debts; private final PaymentService payments; private final ElectronicPaymentRepository attempts; private final CurrentIdentity identity;
    ElectronicPaymentService(DebtRepository debts,PaymentService payments,ElectronicPaymentRepository attempts,CurrentIdentity identity){this.debts=debts;this.payments=payments;this.attempts=attempts;this.identity=identity;}
    ApiDtos.ElectronicPaymentPreview preview(ApiDtos.ElectronicPaymentRequest request){Debt debt=debt(request.debtId());identity.requireOwnership(debt.taxpayerId);BigDecimal payable=request.amount().min(debt.outstandingBalance).setScale(2,RoundingMode.HALF_UP);boolean approved=debt.status!=DebtStatus.PAID&&debt.status!=DebtStatus.CANCELLED&&payable.signum()>0;return new ApiDtos.ElectronicPaymentPreview(debt.id,request.amount(),payable,approved,approved?"Pago disponible":"La deuda no admite pago");}
    @Transactional ElectronicPaymentAttempt create(ApiDtos.ElectronicPaymentRequest request){CatalogService.require(request.paymentMethod()==PaymentMethod.CARD||request.paymentMethod()==PaymentMethod.DIGITAL_WALLET,"INVALID_ELECTRONIC_PAYMENT_METHOD","El pago electrónico requiere tarjeta o billetera digital");Debt debt=debt(request.debtId());identity.requireOwnership(debt.taxpayerId);ApiDtos.ElectronicPaymentPreview preview=preview(request);CatalogService.require(preview.approved(),"ELECTRONIC_PAYMENT_REJECTED",preview.message());Payment payment=payments.registerElectronic(new ApiDtos.RegisterPaymentRequest(debt.taxpayerId,null,request.paymentMethod(),request.amount(),List.of(new ApiDtos.AllocationRequest(debt.id,request.amount()))));ElectronicPaymentAttempt attempt=new ElectronicPaymentAttempt();attempt.paymentId=payment.id;attempt.taxpayerId=debt.taxpayerId;attempt.debtId=debt.id;attempt.amount=payment.amount;attempt.status=ElectronicPaymentStatus.APPROVED;attempt.gatewayReference="SIM-"+UUID.randomUUID();attempt.createdAt=OffsetDateTime.now();return attempts.save(attempt);}
    ElectronicPaymentAttempt getByPayment(Long paymentId){ElectronicPaymentAttempt attempt=attempts.findByPaymentId(paymentId).orElseThrow(()->CatalogService.notFound("Pago electrónico"));identity.requireOwnership(attempt.taxpayerId);return attempt;}
    private Debt debt(Long id){return debts.findById(id).orElseThrow(()->CatalogService.notFound("Deuda"));}
}

@Service
class CreditBalanceService {
    private final CreditBalanceRepository credits;private final CreditBalanceApplicationRepository applications;private final DebtRepository debts;private final PaymentPlanRepository plans;private final PaymentService payments;private final CurrentIdentity identity;private final AuditService audit;
    private final PaymentPlanDebtRepository planDebts;
    CreditBalanceService(CreditBalanceRepository credits,CreditBalanceApplicationRepository applications,DebtRepository debts,PaymentPlanRepository plans,PaymentService payments,CurrentIdentity identity,AuditService audit,PaymentPlanDebtRepository planDebts){this.credits=credits;this.applications=applications;this.debts=debts;this.plans=plans;this.payments=payments;this.identity=identity;this.audit=audit;this.planDebts=planDebts;}
    @Transactional CreditBalanceApplication apply(Long creditId,ApiDtos.ApplyCreditBalanceRequest request){CreditBalance credit=credits.findByIdForUpdate(creditId).orElseThrow(()->CatalogService.notFound("Saldo a favor"));Debt debt=debts.findByIdForUpdate(request.debtId()).orElseThrow(()->CatalogService.notFound("Deuda"));CatalogService.require(credit.taxpayerId.equals(debt.taxpayerId),"CREDIT_TAXPAYER_MISMATCH","El saldo y la deuda pertenecen a contribuyentes distintos");CatalogService.require(!plans.existsByDebtIdAndStatus(debt.id,PaymentPlanStatus.ACTIVE)&&!planDebts.existsByDebtIdAndStatus(debt.id,PaymentPlanDebtStatus.ACTIVE),"DEBT_IN_ACTIVE_PAYMENT_PLAN","La deuda pertenece a un plan activo");BigDecimal amount=PaymentService.money(request.amount());CatalogService.require(amount.compareTo(credit.availableAmount)<=0,"INSUFFICIENT_CREDIT_BALANCE","El saldo a favor es insuficiente");CatalogService.require(amount.compareTo(debt.outstandingBalance)<=0,"CREDIT_EXCEEDS_DEBT","La aplicación supera el saldo de deuda");CreditBalanceApplication a=new CreditBalanceApplication();a.creditBalanceId=credit.id;a.debtId=debt.id;a.amount=amount;a.status="ACTIVE";a.appliedBy=identity.get().userId();a.appliedAt=OffsetDateTime.now();applications.save(a);credit.availableAmount=credit.availableAmount.subtract(amount);credit.status=credit.availableAmount.signum()==0?CreditBalanceStatus.USED:CreditBalanceStatus.PARTIALLY_USED;credit.updatedAt=OffsetDateTime.now();debt.outstandingBalance=debt.outstandingBalance.subtract(amount);debt.status=debt.outstandingBalance.signum()==0?DebtStatus.PAID:DebtStatus.PARTIALLY_PAID;debt.updatedAt=OffsetDateTime.now();if(debt.status==DebtStatus.PAID)payments.addOutbox("debtSettled","Debt",debt.id,"{\"debtId\":"+debt.id+"}");audit.record("CreditBalanceApplication",a.id,"CREDIT_BALANCE_APPLIED",a);return a;}
}

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
