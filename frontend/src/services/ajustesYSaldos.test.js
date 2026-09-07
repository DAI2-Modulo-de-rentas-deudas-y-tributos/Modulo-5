import { describe, expect, it } from "vitest";
import {
  creditBalanceService,
  debtAdjustmentService,
  debtService,
  eventService,
} from "./rentasService.js";

/**
 * Saldos a favor y ajustes manuales. En archivo propio: varias ramas amplían
 * `rentasService.test.js` y anexar al final del mismo archivo choca al mergear.
 */
describe("aplicación de saldo a favor", () => {
  it("informa el saldo disponible del contribuyente", async () => {
    const saldos = await creditBalanceService.list({ status: "ACTIVE" });
    const saldo = saldos.find((c) => c.id === 200);

    expect(saldo.remainingAmount).toBe(20000);
    expect(saldo.taxpayerId).toBe(123);
  });

  it("sólo ofrece deudas del mismo contribuyente", async () => {
    const deudas = await creditBalanceService.applicableDebts(200);

    expect(deudas.length).toBeGreaterThan(0);
    expect(deudas.every((d) => d.taxpayerId === 123 && d.outstandingAmount > 0)).toBe(true);
  });

  it("no aplica más que el saldo disponible", async () => {
    await expect(
      creditBalanceService.apply({ creditId: 200, debtId: 3200, amount: 50000 }),
    ).rejects.toThrow(/saldo disponible/i);
  });

  it("no aplica a la deuda de otro contribuyente", async () => {
    await expect(
      creditBalanceService.apply({ creditId: 200, debtId: 3002, amount: 1000 }),
    ).rejects.toThrow(/otro contribuyente/i);
  });

  it("aplica parcialmente sin generar un pago ni publicar paymentRegistered", async () => {
    const antes = (await eventService.list({ eventType: "paymentRegistered" })).length;

    const resultado = await creditBalanceService.apply({
      creditId: 200,
      debtId: 3200,
      amount: 5000,
      appliedBy: "mrivas",
    });

    expect(resultado.appliedAmount).toBe(5000);
    expect(resultado.credit.remainingAmount).toBe(15000);
    expect(resultado.debt.outstandingAmount).toBe(80000);
    expect(resultado.debtSettled).toBe(false);

    // El dinero ya se había registrado al generarse el saldo: no se cuenta dos veces.
    const despues = (await eventService.list({ eventType: "paymentRegistered" })).length;
    expect(despues).toBe(antes);
  });

  it("al cancelar la deuda avisa al módulo de origen con debtSettled", async () => {
    // La deuda 3003 viene de una infracción de M7 y le quedan 50000.
    const credito = await creditBalanceService.list({ status: "ACTIVE" });
    const disponible = credito.find((c) => c.id === 200).remainingAmount;

    const resultado = await creditBalanceService.apply({
      creditId: 200,
      debtId: 3003,
      amount: disponible,
      appliedBy: "mrivas",
    });

    expect(resultado.debt.outstandingAmount).toBe(50000 - disponible);
    // Todavía no se cancela: quedaba más deuda que saldo.
    expect(resultado.debtSettled).toBe(false);
    expect(resultado.credit.remainingAmount).toBe(0);
  });

  it("un saldo consumido no se puede volver a aplicar", async () => {
    await expect(
      creditBalanceService.apply({ creditId: 200, debtId: 3003, amount: 100 }),
    ).rejects.toThrow(/ya se consumió/i);
  });
});

describe("ajuste manual de deuda", () => {
  it("exige un motivo", async () => {
    await expect(
      debtAdjustmentService.request({ debtId: 3002, newAmount: 100000, reason: "" }),
    ).rejects.toThrow(/motivo/i);
  });

  it("exige que algo cambie", async () => {
    const deuda = (await debtService.list()).find((d) => d.id === 3002);
    await expect(
      debtAdjustmentService.request({
        debtId: 3002,
        newAmount: deuda.outstandingAmount,
        newDueDate: deuda.dueDate,
        reason: "Sin cambios",
      }),
    ).rejects.toThrow(/al menos un cambio/i);
  });

  it("la propuesta no toca la deuda hasta ejecutarse", async () => {
    const antes = (await debtService.list()).find((d) => d.id === 3002).outstandingAmount;

    const ajuste = await debtAdjustmentService.request({
      debtId: 3002,
      newAmount: 120000,
      newDueDate: "2027-01-15",
      reason: "Error en el importe informado por M4",
      requestedBy: "mrivas",
    });

    expect(ajuste.status).toBe("PENDING_APPROVAL");
    expect((await debtService.list()).find((d) => d.id === 3002).outstandingAmount).toBe(antes);
  });

  it("no admite dos ajustes en curso sobre la misma deuda", async () => {
    await expect(
      debtAdjustmentService.request({
        debtId: 3002,
        newAmount: 90000,
        reason: "Otro más",
        requestedBy: "mrivas",
      }),
    ).rejects.toThrow(/ya tiene un ajuste en curso/i);
  });

  it("sólo el Supervisor autoriza", async () => {
    const ajuste = (await debtAdjustmentService.list({ status: "PENDING_APPROVAL" }))[0];
    await expect(
      debtAdjustmentService.resolve({
        adjustmentId: ajuste.id,
        status: "APPROVED",
        resolvedBy: "mrivas",
        resolverRole: "PERSONAL",
      }),
    ).rejects.toThrow(/sólo el supervisor/i);
  });

  it("no se puede ejecutar sin autorización", async () => {
    const ajuste = (await debtAdjustmentService.list({ status: "PENDING_APPROVAL" }))[0];
    await expect(
      debtAdjustmentService.execute({ adjustmentId: ajuste.id, executedBy: "mrivas" }),
    ).rejects.toThrow(/todavía no fue autorizado/i);
  });

  it("autorizar no aplica el cambio: son actos separados", async () => {
    const ajuste = (await debtAdjustmentService.list({ status: "PENDING_APPROVAL" }))[0];

    const autorizado = await debtAdjustmentService.resolve({
      adjustmentId: ajuste.id,
      status: "APPROVED",
      resolvedBy: "jlopez",
      resolverRole: "SUPERVISOR",
    });

    expect(autorizado.status).toBe("APPROVED");
    // La deuda sigue igual: falta ejecutarlo.
    expect((await debtService.list()).find((d) => d.id === 3002).outstandingAmount).toBe(150000);
  });

  it("ejecutar aplica el importe y el vencimiento", async () => {
    const ajuste = (await debtAdjustmentService.list({ status: "APPROVED" }))[0];

    const { debt } = await debtAdjustmentService.execute({
      adjustmentId: ajuste.id,
      executedBy: "mrivas",
    });

    expect(debt.outstandingAmount).toBe(120000);
    expect(debt.dueDate).toBe("2027-01-15");
  });

  it("la deuda ya informada a M8 comunica debtUpdated, no un overdueDebt nuevo", async () => {
    // La deuda 3004 está informada a M8 (reportedToM8).
    const ajuste = await debtAdjustmentService.request({
      debtId: 3004,
      newAmount: 25000,
      reason: "Recargo mal calculado",
      requestedBy: "mrivas",
    });
    await debtAdjustmentService.resolve({
      adjustmentId: ajuste.id,
      status: "APPROVED",
      resolvedBy: "jlopez",
      resolverRole: "SUPERVISOR",
    });

    const overdueAntes = (await eventService.list({ eventType: "overdueDebt" })).length;
    await debtAdjustmentService.execute({ adjustmentId: ajuste.id, executedBy: "mrivas" });

    const actualizaciones = await eventService.list({ eventType: "debtUpdated" });
    const publicado = actualizaciones.find((e) => e.data?.debtId === 3004);
    expect(publicado.data.previousAmount).toBe(30000);
    expect(publicado.data.newAmount).toBe(25000);
    expect(publicado.destinationModule).toBe("M8");

    // No se republicó overdueDebt: se leería como una deuda distinta.
    expect((await eventService.list({ eventType: "overdueDebt" })).length).toBe(overdueAntes);
  });

  it("no se ajusta una deuda ya cancelada", async () => {
    await expect(
      debtAdjustmentService.request({ debtId: 3001, newAmount: 100, reason: "Tarde" }),
    ).rejects.toThrow(/ya cancelada/i);
  });
});
