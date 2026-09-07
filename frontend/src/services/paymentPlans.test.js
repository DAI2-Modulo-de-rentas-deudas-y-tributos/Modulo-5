import { describe, expect, it } from "vitest";
import { paymentPlanService } from "./rentasService.js";

/**
 * Derivación de una solicitud de plan al Supervisor.
 *
 * En archivo propio a propósito: `rentasService.test.js` lo amplían varias ramas a
 * la vez y anexar al final del mismo archivo genera conflictos de merge.
 */
describe("derivación al supervisor", () => {
  it("las solicitudes sin derivar se leen como en revisión", async () => {
    const plans = await paymentPlanService.list({ status: "REQUESTED" });

    // No hace falta marcarlas en el dataset: el estado interno se deriva del contrato.
    expect(plans.every((p) => p.internalStatus === "PENDING_REVIEW")).toBe(true);
  });

  it("exige un motivo para derivar", async () => {
    await expect(
      paymentPlanService.escalate({ requestId: 800, escalatedBy: "mrivas", note: "" }),
    ).rejects.toThrow(/por qué la derivás/i);
  });

  it("deriva la solicitud sin publicar ningún evento", async () => {
    const plan = await paymentPlanService.escalate({
      requestId: 800,
      escalatedBy: "mrivas",
      note: "Pide más cuotas de las habituales.",
    });

    expect(plan.internalStatus).toBe("PENDING_SUPERVISOR");
    expect(plan.escalatedBy).toBe("mrivas");
    // Para el exterior sigue pendiente: el contrato no conoce la derivación.
    expect(plan.status).toBe("REQUESTED");
  });

  it("no deriva dos veces la misma solicitud", async () => {
    await expect(
      paymentPlanService.escalate({ requestId: 800, escalatedBy: "mrivas", note: "otra vez" }),
    ).rejects.toThrow(/ya está derivada/i);
  });

  it("impide que un analista resuelva una solicitud derivada", async () => {
    await expect(
      paymentPlanService.resolve({
        requestId: 800,
        status: "GRANTED",
        resolvedBy: "mrivas",
        resolverRole: "PERSONAL",
      }),
    ).rejects.toThrow(/sólo el supervisor puede resolverla/i);
  });

  it("el supervisor resuelve la derivada y ahí sí se publica el estado final", async () => {
    const plan = await paymentPlanService.resolve({
      requestId: 800,
      status: "GRANTED",
      installments: 6,
      resolvedBy: "jlopez",
      resolverRole: "SUPERVISOR",
    });

    expect(plan.status).toBe("GRANTED");
    expect(plan.internalStatus).toBe("APPROVED");
    expect(plan.planId).toBeTruthy();
  });

  it("no se puede derivar una solicitud ya resuelta", async () => {
    await expect(
      paymentPlanService.escalate({ requestId: 800, escalatedBy: "mrivas", note: "tarde" }),
    ).rejects.toThrow(/ya fue resuelta/i);
  });

  it("filtra el listado por estado interno", async () => {
    const derivadas = await paymentPlanService.list({ internalStatus: "PENDING_SUPERVISOR" });

    expect(derivadas.every((p) => p.internalStatus === "PENDING_SUPERVISOR")).toBe(true);
  });
});
