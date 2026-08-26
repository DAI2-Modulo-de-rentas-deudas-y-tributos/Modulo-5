import { describe, expect, it } from "vitest";
import {
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
    expect(plan.totalAmount).toBe(plan.totalDebt * 1.1);
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
