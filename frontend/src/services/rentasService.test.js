import { describe, expect, it } from "vitest";
import {
  auditService,
  portalService,
  billService,
  cashierService,
  debtService,
  exemptionService,
  paymentPlanService,
  paymentService,
  settlementService,
  ticketService,
} from "./rentasService.js";

/**
 * Reglas de negocio del área. El store de mocks es compartido, por eso cada test
 * trabaja sobre entidades distintas o crea las suyas.
 */

describe("liquidaciones", () => {
  it("aplica el descuento del beneficio social cuando alcanza al concepto", async () => {
    const settlement = await settlementService.generate({
      taxpayerId: 123,
      conceptCode: "TASA_SERVICIOS",
      period: "2026-10",
      baseAmount: 100000,
      dueDate: "2026-10-15",
    });

    expect(settlement.discountPercentage).toBe(50);
    expect(settlement.amount).toBe(50000);
    expect(settlement.status).toBe("DRAFT");
  });

  it("no aplica descuento a un concepto fuera del beneficio", async () => {
    const settlement = await settlementService.generate({
      taxpayerId: 123,
      conceptCode: "PATENTE",
      period: "2026-10",
      baseAmount: 100000,
      dueDate: "2026-10-15",
    });

    expect(settlement.discountPercentage).toBe(0);
    expect(settlement.amount).toBe(100000);
  });

  it("genera la deuda recién al emitir la liquidación", async () => {
    const settlement = await settlementService.generate({
      taxpayerId: 78,
      conceptCode: "ABL",
      period: "2026-11",
      baseAmount: 80000,
      dueDate: "2026-11-10",
    });

    const before = await debtService.list({ taxpayerId: 78 });
    await settlementService.issue(settlement.id);
    const after = await debtService.list({ taxpayerId: 78 });

    expect(after.length).toBe(before.length + 1);
    const created = after.find((d) => d.originId === settlement.id);
    expect(created.originType).toBe("SETTLEMENT");
    expect(created.outstandingAmount).toBe(80000);
  });

  it("rechaza emitir dos veces la misma liquidación", async () => {
    const settlement = await settlementService.generate({
      taxpayerId: 78,
      conceptCode: "ABL",
      period: "2026-12",
      baseAmount: 10000,
      dueDate: "2026-12-10",
    });
    await settlementService.issue(settlement.id);

    await expect(settlementService.issue(settlement.id)).rejects.toThrow(/borrador/i);
  });
});

describe("pagos", () => {
  async function createDebt(amount) {
    const settlement = await settlementService.generate({
      taxpayerId: 78,
      conceptCode: "ABL",
      period: "2027-01",
      baseAmount: amount,
      dueDate: "2027-01-10",
    });
    await settlementService.issue(settlement.id);
    const debts = await debtService.list({ taxpayerId: 78 });
    return debts.find((d) => d.originId === settlement.id);
  }

  it("descuenta el importe del saldo de la deuda", async () => {
    const debt = await createDebt(100000);

    const payment = await paymentService.register({
      taxpayerId: 78,
      debtId: debt.id,
      amountPaid: 40000,
      channel: "VENTANILLA",
    });

    expect(payment.status).toBe("REGISTERED");
    expect(payment.remainingBalance).toBe(60000);

    const [updated] = (await debtService.list({ taxpayerId: 78 })).filter((d) => d.id === debt.id);
    expect(updated.outstandingAmount).toBe(60000);
    expect(updated.status).not.toBe("SETTLED");
  });

  it("cancela la deuda cuando el pago cubre el saldo", async () => {
    const debt = await createDebt(50000);

    await paymentService.register({
      taxpayerId: 78,
      debtId: debt.id,
      amountPaid: 50000,
      channel: "TRANSFERENCIA",
    });

    const [updated] = (await debtService.list({ taxpayerId: 78 })).filter((d) => d.id === debt.id);
    expect(updated.outstandingAmount).toBe(0);
    expect(updated.status).toBe("SETTLED");
  });

  it("rechaza un pago mayor al saldo de la deuda", async () => {
    const debt = await createDebt(30000);

    await expect(
      paymentService.register({
        taxpayerId: 78,
        debtId: debt.id,
        amountPaid: 30001,
        channel: "VENTANILLA",
      }),
    ).rejects.toThrow(/supera el saldo/i);
  });

  it("registra sin imputar cuando no se identifica la deuda", async () => {
    const payment = await paymentService.register({
      taxpayerId: 123,
      debtId: "",
      amountPaid: 15000,
      channel: "HOMEBANKING",
    });

    expect(payment.status).toBe("UNALLOCATED");
    expect(payment.allocated).toBe(false);
    expect(payment.debtId).toBeNull();
  });

  it("no permite imputar un pago a la deuda de otro contribuyente", async () => {
    const debt = await createDebt(20000);
    const payment = await paymentService.register({
      taxpayerId: 123,
      debtId: "",
      amountPaid: 5000,
      channel: "HOMEBANKING",
    });

    await expect(
      paymentService.allocate({ paymentId: payment.id, debtId: debt.id }),
    ).rejects.toThrow(/otro contribuyente/i);
  });

  it("devuelve el saldo a la deuda al reversar el pago", async () => {
    const debt = await createDebt(70000);
    const payment = await paymentService.register({
      taxpayerId: 78,
      debtId: debt.id,
      amountPaid: 70000,
      channel: "VENTANILLA",
    });

    await paymentService.reverse({ paymentId: payment.id, reason: "Pago registrado por error" });

    const [updated] = (await debtService.list({ taxpayerId: 78 })).filter((d) => d.id === debt.id);
    expect(updated.outstandingAmount).toBe(70000);
    expect(updated.status).not.toBe("SETTLED");
  });

  it("exige motivo para reversar", async () => {
    const debt = await createDebt(10000);
    const payment = await paymentService.register({
      taxpayerId: 78,
      debtId: debt.id,
      amountPaid: 10000,
      channel: "VENTANILLA",
    });

    await expect(
      paymentService.reverse({ paymentId: payment.id, reason: "   " }),
    ).rejects.toThrow(/motivo/i);
  });
});

describe("planes de pago", () => {
  it("suma 5% de interés por cada tramo de tres cuotas", () => {
    expect(paymentPlanService.simulate({ totalDebt: 100000, installments: 3 })).toMatchObject({
      interestRate: 0.05,
      totalAmount: 105000,
    });
    expect(paymentPlanService.simulate({ totalDebt: 100000, installments: 6 }).totalAmount).toBe(
      110000,
    );
  });

  it("exige motivo al rechazar la solicitud", async () => {
    await expect(
      paymentPlanService.resolve({ requestId: 800, status: "REJECTED", reason: "" }),
    ).rejects.toThrow(/motivo/i);
  });

  it("otorga el plan y calcula el total con interés", async () => {
    const plan = await paymentPlanService.resolve({
      requestId: 800,
      status: "GRANTED",
      installments: 6,
      resolvedBy: "jlopez",
    });

    expect(plan.status).toBe("GRANTED");
    expect(plan.planId).toBeTruthy();
    // El servicio redondea a centavos: comparar contra el float crudo es frágil.
    expect(plan.totalAmount).toBeCloseTo(plan.totalDebt * 1.1, 2);
  });

  it("no permite resolver dos veces la misma solicitud", async () => {
    await expect(
      paymentPlanService.resolve({ requestId: 800, status: "GRANTED", installments: 3 }),
    ).rejects.toThrow(/ya fue resuelta/i);
  });
});

describe("exenciones", () => {
  it("exige motivo al rechazar", async () => {
    await expect(
      exemptionService.resolve({ requestId: 600, status: "REJECTED", reason: "" }),
    ).rejects.toThrow(/motivo/i);
  });

  it("aprueba con la vigencia solicitada por defecto", async () => {
    const exemption = await exemptionService.resolve({
      requestId: 600,
      status: "APPROVED",
      resolvedBy: "jlopez",
    });

    expect(exemption.exemptionId).toBeTruthy();
    expect(exemption.percentage).toBe(100);
    expect(exemption.validFrom).toBe("2026-09-01");
    expect(exemption.validUntil).toBe("2027-08-31");
  });
});

describe("tickets", () => {
  it("exige motivo al rechazar el reclamo", async () => {
    await expect(
      ticketService.updateStatus({ ticketId: 1002, status: "REJECTED", reason: "" }),
    ).rejects.toThrow(/motivo/i);
  });

  it("actualiza el estado y asigna al agente", async () => {
    const ticket = await ticketService.updateStatus({
      ticketId: 1002,
      status: "IN_PROGRESS",
      assignedTo: "mrivas",
    });

    expect(ticket.status).toBe("IN_PROGRESS");
    expect(ticket.assignedTo).toBe("mrivas");
  });
});

describe("caja", () => {
  it("busca al contribuyente por documento y devuelve su deuda consolidada", async () => {
    const results = await cashierService.search({ query: "40111222" });

    const taxpayer = results.find((r) => r.kind === "TAXPAYER");
    expect(taxpayer.title).toBe("Juan Pérez");
    expect(taxpayer.amount).toBeGreaterThan(0);
  });

  it("busca por N° de boleta y muestra a qué contribuyente está vinculada", async () => {
    const results = await cashierService.search({ query: "12001" });

    expect(results[0].kind).toBe("BILL");
    expect(results[0].subtitle).toMatch(/Juan Pérez/);
    expect(results[0].taxpayerId).toBe(123);
  });

  it("preselecciona la deuda cuando el cobro entra por una boleta", async () => {
    const context = await cashierService.chargeContext({ kind: "BILL", id: 12002 });

    expect(context.taxpayer.id).toBe(78);
    expect(context.bill.id).toBe(12002);
    expect(context.selectedDebtId).toBe(3002);
    expect(context.debts).toHaveLength(1);
  });

  it("exige el medio de pago para cobrar en ventanilla", async () => {
    await expect(
      cashierService.registerCounterPayment({ debtId: 3003, amountPaid: 1000 }),
    ).rejects.toThrow(/medio de pago/i);
  });

  it("rechaza cobrar una deuda sin saldo", async () => {
    await expect(
      cashierService.registerCounterPayment({
        debtId: 3001,
        amountPaid: 1000,
        method: "EFECTIVO",
        registeredBy: "pcabrera",
      }),
    ).rejects.toThrow(/cancelada/i);
  });

  it("cobra la deuda y devuelve el comprobante con el responsable", async () => {
    const settlement = await settlementService.generate({
      taxpayerId: 78,
      conceptCode: "ABL",
      period: "2026-12",
      baseAmount: 40000,
      dueDate: "2026-12-10",
    });
    await settlementService.issue(settlement.id);
    const debt = (await debtService.list({ taxpayerId: 78 })).find(
      (d) => d.originId === settlement.id,
    );

    const receipt = await cashierService.registerCounterPayment({
      debtId: debt.id,
      amountPaid: 40000,
      method: "EFECTIVO",
      registeredBy: "pcabrera",
    });

    expect(receipt.receiptNumber).toMatch(/^REC-2026-/);
    expect(receipt.amountPaid).toBe(40000);
    expect(receipt.remainingBalance).toBe(0);
    expect(receipt.settled).toBe(true);
    expect(receipt.wasOverdue).toBe(false);
    expect(receipt.cashier.fullName).toBe("Paula Cabrera");
    expect(receipt.channel).toBe("VENTANILLA");
  });

  it("cancela la boleta junto con la deuda que la originó", async () => {
    const bills = await billService.list({ taxpayerId: "78" });
    expect(bills.find((b) => b.id === 12002).status).toBe("ISSUED");

    await cashierService.registerCounterPayment({
      debtId: 3002,
      amountPaid: 150000,
      method: "TRANSFERENCIA",
      registeredBy: "pcabrera",
    });

    const after = await billService.list({ taxpayerId: "78" });
    expect(after.find((b) => b.id === 12002).status).toBe("SETTLED");
  });

  it("suma el cobro a la jornada del cajero", async () => {
    const before = await cashierService.dailySummary({ registeredBy: "pcabrera" });

    const settlement = await settlementService.generate({
      taxpayerId: 190,
      conceptCode: "PATENTE",
      period: "2026-12",
      baseAmount: 5000,
      dueDate: "2026-12-20",
    });
    await settlementService.issue(settlement.id);
    const debt = (await debtService.list({ taxpayerId: 190 })).find(
      (d) => d.originId === settlement.id,
    );
    await cashierService.registerCounterPayment({
      debtId: debt.id,
      amountPaid: 5000,
      method: "QR",
      registeredBy: "pcabrera",
    });

    const after = await cashierService.dailySummary({ registeredBy: "pcabrera" });

    expect(after.registeredCount).toBe(before.registeredCount + 1);
    expect(after.totalCollected).toBe(before.totalCollected + 5000);
    expect(after.latest[0].amountPaid).toBe(5000);
  });

  it("reconstruye el comprobante de un pago ya registrado", async () => {
    const receipt = await cashierService.receipt(9005);

    expect(receipt.receiptNumber).toBe("REC-2026-9005");
    expect(receipt.taxpayer.name).toBe("Juan Pérez");
    expect(receipt.method).toBe("TARJETA_DEBITO");
    expect(receipt.cashier.counter).toMatch(/Caja 3/);
  });

  it("arma la ficha del contribuyente con deudas, pagos y boletas", async () => {
    const file = await cashierService.taxpayerFile(123);

    expect(file.taxpayer.name).toBe("Juan Pérez");
    expect(file.debts.length).toBeGreaterThan(0);
    expect(file.payments.length).toBeGreaterThan(0);
    expect(file.totals.outstanding).toBeGreaterThanOrEqual(0);
  });
});

/**
 * Auditoría. Son consultas: ninguna de estas operaciones modifica el store, por eso
 * pueden correr sobre el dataset compartido sin preparar entidades propias.
 */
describe("auditoría", () => {
  it("no expone ninguna operación de escritura", () => {
    // El área es de sólo lectura: si aparece un verbo de escritura, es un error de diseño.
    const writeVerbs = /^(register|create|update|resolve|issue|reverse|allocate|retry|delete|generate)/;
    const offenders = Object.keys(auditService).filter((name) => writeVerbs.test(name));

    expect(offenders).toEqual([]);
  });

  it("resume la jornada con los desvíos que hay que mirar", async () => {
    const dashboard = await auditService.dashboard();

    expect(dashboard.paymentsReversed).toBeGreaterThan(0);
    expect(dashboard.integrationErrors).toBeGreaterThan(0);
    expect(dashboard.recentActivity.length).toBeGreaterThan(0);
    // La actividad viene de la más reciente a la más vieja.
    const [first, second] = dashboard.recentActivity;
    expect(new Date(first.at) >= new Date(second.at)).toBe(true);
  });

  it("arma la ficha del contribuyente con sus cuatro frentes y el saldo a favor", async () => {
    const file = await auditService.taxpayerFile(123);

    expect(file.taxpayer.name).toBe("Juan Pérez");
    expect(file.debts.length).toBeGreaterThan(0);
    expect(file.payments.length).toBeGreaterThan(0);
    expect(file.exemptions.length).toBeGreaterThan(0);
    expect(file.totals.creditBalance).toBe(20000);
    // Los listados llegan con el nombre del concepto resuelto, no sólo el código.
    expect(file.debts[0].conceptName).toBeTruthy();
  });

  it("devuelve el concepto con su historial de versiones", async () => {
    const concept = await auditService.conceptDetail("TASA_SERVICIOS");

    expect(concept.type).toBe("TASA");
    expect(concept.calculationType).toBe("PORCENTAJE");
    expect(concept.versions.length).toBe(3);
    expect(concept.versions[0].status).toBe("ACTIVE");
  });

  it("traza la liquidación externa hasta el evento que la originó", async () => {
    const settlement = await auditService.settlementDetail(7007);

    expect(settlement.origin.module).toBe("M7");
    expect(settlement.origin.event).toBe("infractionConfirmed");
    expect(settlement.origin.externalRef).toBe("infractionId:850");
    expect(settlement.debt.id).toBe(3003);
  });

  it("compone el saldo de la deuda y lista los pagos que la tocaron", async () => {
    const debt = await auditService.debtDetail(3004);

    expect(debt.originalAmount).toBe(96000);
    expect(debt.paidAmount).toBe(66000);
    expect(debt.outstandingAmount).toBe(30000);
    expect(debt.payments.map((p) => p.id)).toEqual([9006, 9002]);
    expect(debt.history.length).toBeGreaterThan(1);
  });

  it("muestra la imputación del pago y su reversión", async () => {
    const payment = await auditService.paymentDetail(9004);

    expect(payment.allocations[0].debtId).toBe(3005);
    expect(payment.reversal.id).toBe(500);
    expect(payment.status).toBe("REVERSED");
  });

  it("deja ver los dos estados que deshizo la reversión", async () => {
    const reversal = await auditService.reversalDetail(500);

    expect(reversal.requestedBy).toBe("pcabrera");
    expect(reversal.approvedBy).toBe("jlopez");
    expect(reversal.paymentStatusChange).toEqual({ from: "REGISTERED", to: "REVERSED" });
    expect(reversal.debtStatusChange).toEqual({ from: "SETTLED", to: "OVERDUE" });
    expect(reversal.eventPublished).toBe("paymentReversed");
  });

  it("separa la resolución del plan de su ciclo interno", async () => {
    const granted = await auditService.planDetail(801);
    const defaulted = await auditService.planDetail(803);

    expect(granted.status).toBe("GRANTED");
    expect(granted.lifecycle).toBe("CURRENT");
    expect(granted.schedule.length).toBe(6);
    expect(granted.debts[0].id).toBe(3002);

    expect(defaulted.status).toBe("GRANTED");
    expect(defaulted.lifecycle).toBe("DEFAULTED");
  });

  it("expone la brecha entre el porcentaje solicitado y el aprobado", async () => {
    const exemption = await auditService.exemptionDetail(603);

    expect(exemption.requestedPercentage).toBe(100);
    expect(exemption.percentage).toBe(75);
    expect(exemption.fileNumber).toBe("EXP-450");
    expect(exemption.benefitId).toBe(400);
  });

  it("devuelve el evento con su payload y el error que lo dejó en DLQ", async () => {
    const event = await auditService.integrationDetail(
      "9c3b5e6d-4d5e-4f70-a143-dd44ee55ff66",
    );

    expect(event.eventType).toBe("permitUpdate");
    expect(event.sourceModule).toBe("M4");
    expect(event.status).toBe("DLQ");
    expect(event.payload.permitId).toBe(250);
    expect(event.attempts).toBe(3);
  });

  it("filtra el registro de auditoría por rol y acción", async () => {
    const supervisorEntries = await auditService.auditTrail({ role: "SUPERVISOR" });
    const reversals = await auditService.auditTrail({ action: "PAYMENT_REVERSED" });

    expect(supervisorEntries.every((e) => e.role === "SUPERVISOR")).toBe(true);
    expect(reversals[0].before["Estado pago"]).toBe("REGISTERED");
    expect(reversals[0].after["Estado pago"]).toBe("REVERSED");
  });

  it("calcula la morosidad sobre la deuda viva", async () => {
    const indicators = await auditService.indicators({});

    const alive = indicators.pendingDebt + indicators.overdueDebt;
    expect(indicators.delinquencyRate).toBeCloseTo((indicators.overdueDebt / alive) * 100, 1);
    expect(indicators.byConcept.length).toBeGreaterThan(0);
    // El gráfico de deuda por concepto llega ordenado de mayor a menor.
    expect(indicators.byConcept[0].amount).toBeGreaterThanOrEqual(
      indicators.byConcept[indicators.byConcept.length - 1].amount,
    );
  });

  it("abre el indicador en las filas que lo componen", async () => {
    const breakdown = await auditService.indicatorBreakdown("overdueDebt", {});

    expect(breakdown.count).toBe(breakdown.rows.length);
    expect(breakdown.rows.every((row) => row.status === "OVERDUE")).toBe(true);
    expect(breakdown.total).toBeGreaterThan(0);
  });
});

/**
 * Portal del contribuyente. El ciudadano consulta su propio legajo y sólo puede
 * iniciar dos trámites; el resto de las operaciones son del municipio.
 */
describe("portal del contribuyente", () => {
  it("sólo expone consultas y los dos trámites que puede iniciar el ciudadano", () => {
    // Registrar pagos, emitir boletas o resolver solicitudes no son atribuciones suyas.
    const prohibidos = /^(register(?!Exemption)|issue|resolve|reverse|allocate|retry)/;
    expect(Object.keys(portalService).filter((m) => prohibidos.test(m))).toEqual([]);

    expect(Object.keys(portalService)).toContain("requestPaymentPlan");
    expect(Object.keys(portalService)).toContain("requestExemption");
  });

  it("resume la cuenta con deuda, próximo vencimiento y saldo a favor", async () => {
    const summary = await portalService.accountSummary(123);

    expect(summary.taxpayer.name).toBe("Juan Pérez");
    expect(summary.totalDebt).toBeGreaterThan(0);
    expect(summary.creditBalance).toBe(20000);
    // El próximo vencimiento es el más cercano entre las obligaciones con saldo.
    expect(summary.nextDueDate.debtId).toBe(3200);
    expect(summary.nextDueDate.overdue).toBe(true);
    expect(summary.obligations.every((o) => o.outstandingAmount > 0)).toBe(true);
  });

  it("avisa primero de la deuda vencida y después de lo informativo", async () => {
    const notices = await portalService.notices(123);

    expect(notices[0].severity).toBe("error");
    expect(notices[0].title).toMatch(/vencida/i);
    // El saldo a favor se informa, no se reclama.
    expect(notices.some((n) => n.id === "saldo-a-favor" && n.severity === "success")).toBe(true);
  });

  it("no deja ver el legajo de otro contribuyente", async () => {
    const debts = await portalService.debts({ taxpayerId: 123 });
    const payments = await portalService.payments({ taxpayerId: 123 });

    expect(debts.every((d) => d.taxpayerId === 123)).toBe(true);
    expect(payments.every((p) => p.taxpayerId === 123)).toBe(true);
  });

  it("marca las deudas que ya están dentro de una solicitud de plan", async () => {
    const debts = await portalService.debts({ taxpayerId: 123 });

    // La solicitud 800 incluye la deuda 3200; la 3003 queda libre para financiar.
    expect(debts.find((d) => d.id === 3200).planRequestId).toBe(800);
    expect(debts.find((d) => d.id === 3003).planRequestId).toBeNull();
  });

  it("pide un plan de pago y publica paymentPlanRequested", async () => {
    const settlement = await settlementService.generate({
      taxpayerId: 145,
      conceptCode: "ABL",
      period: "2027-01",
      baseAmount: 60000,
      dueDate: "2027-01-10",
    });
    await settlementService.issue(settlement.id);
    const debt = (await debtService.list({ taxpayerId: 145 })).find(
      (d) => d.originId === settlement.id,
    );

    const plan = await portalService.requestPaymentPlan({
      taxpayerId: 145,
      debtIds: [debt.id],
      installments: 6,
    });

    expect(plan.status).toBe("REQUESTED");
    expect(plan.totalDebt).toBe(60000);
    expect(plan.installments).toBe(6);
    expect(plan.taxpayerType).toBe("CITIZEN");
  });

  it("rechaza financiar una deuda ajena", async () => {
    await expect(
      portalService.requestPaymentPlan({ taxpayerId: 145, debtIds: [3200], installments: 6 }),
    ).rejects.toThrow(/no te pertenece/i);
  });

  it("rechaza financiar dos veces la misma deuda", async () => {
    await expect(
      portalService.requestPaymentPlan({ taxpayerId: 123, debtIds: [3200], installments: 6 }),
    ).rejects.toThrow(/ya está incluida/i);
  });

  it("exige elegir alguna deuda", async () => {
    await expect(
      portalService.requestPaymentPlan({ taxpayerId: 123, debtIds: [], installments: 6 }),
    ).rejects.toThrow(/al menos una deuda/i);
  });

  it("simula la cuota antes de mandar la solicitud", async () => {
    const simulacion = portalService.simulatePaymentPlan({ totalDebt: 120000, installments: 6 });

    expect(simulacion.installments).toBe(6);
    expect(simulacion.totalAmount).toBeGreaterThan(120000);
    expect(simulacion.installmentAmount).toBeCloseTo(simulacion.totalAmount / 6, 2);
  });

  it("pide una exención a su propio nombre", async () => {
    const exemption = await portalService.requestExemption({
      taxpayerId: 145,
      conceptCode: "PATENTE",
      reason: "Jubilada con haber mínimo",
      requestedPercentage: 50,
      requestedFrom: "2027-01-01",
      requestedUntil: "2027-12-31",
    });

    expect(exemption.citizenId).toBe(145);
    expect(exemption.status).toBe("REQUESTED");
    expect(exemption.requestedPercentage).toBe(50);
  });
});

describe("plan de pago con anticipo", () => {
  it("descuenta el anticipo de la base financiada y abarata la cuota", async () => {
    const sinAnticipo = paymentPlanService.simulate({ totalDebt: 100000, installments: 6 });
    const conAnticipo = paymentPlanService.simulate({
      totalDebt: 100000,
      installments: 6,
      downPayment: 40000,
    });

    expect(conAnticipo.financedAmount).toBe(60000);
    // El interés se calcula sobre lo financiado, no sobre la deuda entera.
    expect(conAnticipo.interestAmount).toBe(6000);
    expect(conAnticipo.totalAmount).toBe(106000);
    expect(conAnticipo.installmentAmount).toBeLessThan(sinAnticipo.installmentAmount);
  });

  it("sin anticipo calcula igual que antes", async () => {
    const simulacion = paymentPlanService.simulate({ totalDebt: 100000, installments: 6 });

    expect(simulacion.downPayment).toBe(0);
    expect(simulacion.financedAmount).toBe(100000);
    expect(simulacion.totalAmount).toBe(110000);
  });

  it("nunca financia más que la deuda aunque el anticipo se pase", async () => {
    const simulacion = paymentPlanService.simulate({
      totalDebt: 50000,
      installments: 3,
      downPayment: 90000,
    });

    expect(simulacion.downPayment).toBe(50000);
    expect(simulacion.financedAmount).toBe(0);
  });

  it("guarda el anticipo que ofrece el contribuyente en la solicitud", async () => {
    const settlement = await settlementService.generate({
      taxpayerId: 78,
      conceptCode: "ABL",
      period: "2027-03",
      baseAmount: 90000,
      dueDate: "2027-03-10",
    });
    await settlementService.issue(settlement.id);
    const debt = (await debtService.list({ taxpayerId: 78 })).find(
      (d) => d.originId === settlement.id,
    );

    const plan = await portalService.requestPaymentPlan({
      taxpayerId: 78,
      debtIds: [debt.id],
      installments: 6,
      downPayment: 20000,
    });

    expect(plan.downPayment).toBe(20000);

    // Al resolver, Rentas respeta el anticipo ofrecido.
    const otorgado = await paymentPlanService.resolve({
      requestId: plan.requestId,
      status: "GRANTED",
      resolvedBy: "jlopez",
    });
    expect(otorgado.financedAmount).toBe(70000);
    expect(otorgado.totalAmount).toBe(97000);
  });
});

describe("documentación de la exención", () => {
  it("guarda los adjuntos como referencia a S3, nunca el binario", async () => {
    const exemption = await portalService.requestExemption({
      taxpayerId: 78,
      conceptCode: "ABL",
      reason: "Entidad de bien público",
      requestedPercentage: 100,
      requestedFrom: "2027-01-01",
      requestedUntil: "2027-12-31",
      attachments: [{ name: "estatuto.pdf" }, { name: "acta.pdf" }],
    });

    expect(exemption.attachments).toHaveLength(2);
    expect(exemption.attachments[0]).toMatch(/^s3:\/\/rentas-documents\/exenciones\//);
    expect(exemption.attachments[0]).toMatch(/estatuto\.pdf$/);
  });

  it("acepta una solicitud sin documentación", async () => {
    const exemption = await portalService.requestExemption({
      taxpayerId: 78,
      conceptCode: "PATENTE",
      reason: "Sin documentación por ahora",
      requestedPercentage: 50,
      requestedFrom: "2027-01-01",
      requestedUntil: "2027-06-30",
    });

    expect(exemption.attachments).toEqual([]);
  });
});
