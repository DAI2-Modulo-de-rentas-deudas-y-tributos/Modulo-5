import { describe, expect, it } from "vitest";
import {
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

/**
 * Generación masiva. La previsualización distingue tres grupos: lo que se genera,
 * lo que queda afuera y lo que se genera pero exige atención.
 */
describe("liquidaciones masivas", () => {
  it("previsualiza el lote con el descuento calculado por contribuyente", async () => {
    const preview = await settlementService.previewBatch({
      conceptCode: "TASA_SERVICIOS",
      period: "2027-01",
      baseAmount: 100000,
      dueDate: "2027-01-15",
    });

    expect(preview.totals.toGenerate).toBe(preview.items.length);
    // Juan Pérez tiene beneficio social del 50% sobre TASA_SERVICIOS.
    const conBeneficio = preview.items.find((i) => i.taxpayerId === 123);
    expect(conBeneficio.discountPercentage).toBe(50);
    expect(conBeneficio.amount).toBe(50000);
    // El resto se liquida por la base completa.
    const sinBeneficio = preview.items.find((i) => i.taxpayerId === 78);
    expect(sinBeneficio.amount).toBe(100000);
  });

  it("deja afuera a quien ya tiene liquidación de ese concepto y período", async () => {
    // La liquidación 7001 es de Juan Pérez, TASA_SERVICIOS, 2026-08.
    const preview = await settlementService.previewBatch({
      conceptCode: "TASA_SERVICIOS",
      period: "2026-08",
      baseAmount: 100000,
      dueDate: "2026-09-15",
    });

    const omitido = preview.errors.find((e) => e.taxpayerId === 123);
    expect(omitido.reason).toMatch(/ya tiene la liquidación #7001/i);
    expect(preview.items.some((i) => i.taxpayerId === 123)).toBe(false);
  });

  it("liquida igual al bloqueado y al fallecido, pero los marca", async () => {
    const preview = await settlementService.previewBatch({
      conceptCode: "ABL",
      period: "2027-02",
      baseAmount: 50000,
      dueDate: "2027-02-15",
    });

    // La obligación existe aunque M1 haya informado bloqueo o fallecimiento.
    expect(preview.items.some((i) => i.taxpayerId === 145)).toBe(true);
    expect(preview.items.some((i) => i.taxpayerId === 190)).toBe(true);
    expect(preview.warnings.map((w) => w.taxpayerId)).toEqual(
      expect.arrayContaining([145, 190]),
    );
  });

  it("acota el lote por tipo de contribuyente", async () => {
    const preview = await settlementService.previewBatch({
      conceptCode: "ABL",
      period: "2027-03",
      baseAmount: 50000,
      dueDate: "2027-03-15",
      taxpayerType: "ORGANIZATION",
    });

    expect(preview.items.every((i) => i.taxpayerType === "ORGANIZATION")).toBe(true);
  });

  it("genera el lote en borrador, sin deuda todavía", async () => {
    const antes = (await debtService.list()).length;

    const result = await settlementService.generateBatch({
      conceptCode: "PATENTE",
      period: "2027-04",
      baseAmount: 40000,
      dueDate: "2027-04-15",
    });

    expect(result.generated.length).toBeGreaterThan(0);
    expect(result.generated.every((s) => s.status === "DRAFT")).toBe(true);
    // El borrador no genera deuda: eso pasa recién al emitir.
    expect((await debtService.list()).length).toBe(antes);
  });

  it("no genera dos veces el mismo lote", async () => {
    const params = {
      conceptCode: "ABL",
      period: "2027-05",
      baseAmount: 30000,
      dueDate: "2027-05-15",
    };
    const primero = await settlementService.generateBatch(params);
    const segundo = await settlementService.previewBatch(params);

    expect(segundo.totals.toGenerate).toBe(0);
    expect(segundo.errors.length).toBe(primero.generated.length);
  });

  it("falla si no queda nadie alcanzado", async () => {
    const params = {
      conceptCode: "ABL",
      period: "2027-06",
      baseAmount: 30000,
      dueDate: "2027-06-15",
    };
    await settlementService.generateBatch(params);

    await expect(settlementService.generateBatch(params)).rejects.toThrow(
      /no hay contribuyentes alcanzados/i,
    );
  });
});
