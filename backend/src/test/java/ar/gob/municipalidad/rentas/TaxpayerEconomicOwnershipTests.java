package ar.gob.municipalidad.rentas;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TaxpayerEconomicOwnershipTests {
    @Autowired MockMvc mvc;
    @Autowired TaxpayerRepository taxpayers;
    @Autowired TaxConceptRepository concepts;
    @Autowired DebtRepository debts;
    @Autowired PaymentRepository payments;
    @Autowired ElectronicPaymentRepository electronicPayments;
    @Autowired CreditBalanceRepository creditBalances;

    @Test void taxpayerCannotReadAnotherTaxpayersPaymentReceiptElectronicPaymentOrCredit() throws Exception {
        TaxpayerReference owner=taxpayer("OWNER");
        TaxpayerReference stranger=taxpayer("STRANGER");
        Debt debt=debt(owner);
        Payment payment=payment(owner);
        ElectronicPaymentAttempt attempt=new ElectronicPaymentAttempt();
        attempt.paymentId=payment.id;
        attempt.taxpayerId=owner.id;
        attempt.debtId=debt.id;
        attempt.amount=payment.amount;
        attempt.status=ElectronicPaymentStatus.APPROVED;
        attempt.gatewayReference="OWNERSHIP-"+UUID.randomUUID();
        attempt.createdAt=OffsetDateTime.now();
        electronicPayments.saveAndFlush(attempt);
        CreditBalance credit=new CreditBalance();
        credit.taxpayerId=owner.id;
        credit.sourcePaymentId=payment.id;
        credit.originalAmount=credit.availableAmount=new BigDecimal("10.00");
        credit.status=CreditBalanceStatus.AVAILABLE;
        credit.createdAt=credit.updatedAt=OffsetDateTime.now();
        creditBalances.saveAndFlush(credit);

        Authentication ownerSession=session("owner",owner.id);
        Authentication strangerSession=session("stranger",stranger.id);

        mvc.perform(get("/api/v1/payments/{id}",payment.id).with(authentication(ownerSession)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.taxpayerId").value(owner.id));
        mvc.perform(get("/api/v1/payments/{id}",payment.id).with(authentication(strangerSession)))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN_OWNERSHIP"))
            .andExpect(jsonPath("$.taxpayerId").doesNotExist());
        mvc.perform(get("/api/v1/taxpayers/{id}/payments",owner.id).with(authentication(ownerSession)))
            .andExpect(status().isOk());
        mvc.perform(get("/api/v1/taxpayers/{id}/payments",owner.id).with(authentication(strangerSession)))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN_OWNERSHIP"));

        mvc.perform(get("/api/v1/payments/{id}/receipt",payment.id).with(authentication(ownerSession)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.paymentId").value(payment.id));
        mvc.perform(get("/api/v1/payments/{id}/receipt",payment.id).with(authentication(strangerSession)))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN_OWNERSHIP"));

        mvc.perform(get("/api/v1/electronic-payments/{paymentId}",payment.id).with(authentication(ownerSession)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.taxpayerId").value(owner.id));
        mvc.perform(get("/api/v1/electronic-payments/{paymentId}",payment.id).with(authentication(strangerSession)))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN_OWNERSHIP"))
            .andExpect(jsonPath("$.gatewayReference").doesNotExist());

        mvc.perform(get("/api/v1/credit-balances/{id}",credit.id).with(authentication(ownerSession)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.taxpayerId").value(owner.id));
        mvc.perform(get("/api/v1/credit-balances/{id}",credit.id).with(authentication(strangerSession)))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN_OWNERSHIP"))
            .andExpect(jsonPath("$.availableAmount").doesNotExist());

        mvc.perform(get("/api/v1/credit-balances/{id}/applications",credit.id).with(authentication(ownerSession)))
            .andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
        mvc.perform(get("/api/v1/credit-balances/{id}/applications",credit.id).with(authentication(strangerSession)))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN_OWNERSHIP"));
        mvc.perform(get("/api/v1/taxpayers/{id}/credit-balances",owner.id).with(authentication(ownerSession)))
            .andExpect(status().isOk());
        mvc.perform(get("/api/v1/taxpayers/{id}/credit-balances",owner.id).with(authentication(strangerSession)))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN_OWNERSHIP"));
    }

    private Authentication session(String username,Long taxpayerId) {
        return new UsernamePasswordAuthenticationToken(new AuthenticatedIdentity(username,taxpayerId),null,
            List.of(new SimpleGrantedAuthority("ROLE_TAXPAYER")));
    }

    private TaxpayerReference taxpayer(String label) {
        TaxpayerReference taxpayer=new TaxpayerReference();
        taxpayer.taxpayerType=TaxpayerType.CITIZEN;
        taxpayer.externalId="SCRUM-699-"+label+"-"+UUID.randomUUID();
        taxpayer.dni=""+Math.abs(UUID.randomUUID().hashCode());
        taxpayer.displayName=label;
        taxpayer.externalStatus=TaxpayerStatus.ACTIVE;
        taxpayer.createdAt=taxpayer.updatedAt=OffsetDateTime.now();
        return taxpayers.saveAndFlush(taxpayer);
    }

    private Debt debt(TaxpayerReference taxpayer) {
        TaxConcept concept=new TaxConcept();
        concept.code="SCRUM-699-"+UUID.randomUUID();
        concept.name=concept.code;
        concept.type=TaxConceptType.FEE;
        concept.originModule="M5";
        concept.active=true;
        concept.createdAt=concept.updatedAt=OffsetDateTime.now();
        concepts.saveAndFlush(concept);
        Debt debt=new Debt();
        debt.taxpayerId=taxpayer.id;
        debt.taxConceptId=concept.id;
        debt.originType=DebtOriginType.LIQUIDATION;
        debt.originalAmount=debt.currentAmount=debt.outstandingBalance=new BigDecimal("10.00");
        debt.dueDate=LocalDate.now().plusDays(30);
        debt.status=DebtStatus.PENDING;
        debt.createdAt=debt.updatedAt=OffsetDateTime.now();
        return debts.saveAndFlush(debt);
    }

    private Payment payment(TaxpayerReference taxpayer) {
        Payment payment=new Payment();
        payment.taxpayerId=taxpayer.id;
        payment.paymentMethod=PaymentMethod.TRANSFER;
        payment.amount=new BigDecimal("10.00");
        payment.allocatedAmount=BigDecimal.ZERO.setScale(2);
        payment.unallocatedAmount=payment.amount;
        payment.status=PaymentStatus.CONFIRMED;
        payment.allocationStatus=PaymentAllocationStatus.UNALLOCATED;
        payment.origin=PaymentOrigin.ELECTRONIC;
        payment.receiptNumber="SCRUM-699-"+UUID.randomUUID();
        payment.registeredBy="test";
        payment.paidAt=payment.createdAt=OffsetDateTime.now();
        return payments.saveAndFlush(payment);
    }
}
