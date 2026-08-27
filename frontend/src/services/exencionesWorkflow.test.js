import { describe, expect, it } from "vitest";
import { eventService, exemptionService } from "./rentasService.js";

/**
 * Pasos internos del trámite de exención. M8 sólo conoce la resolución final:
 * pedir documentación y enviar a resolución no salen del módulo.
 */
describe("trámite de documentación de una exención", () => {
  it("las solicitudes sin gestionar arrancan en revisión", async () => {
    const solicitudes = await exemptionService.list({ status: "REQUESTED" });

    expect(solicitudes.every((e) => e.internalStatus === "PENDING_REVIEW")).toBe(true);
  });

  it("pedir documentación exige decir cuál falta", async () => {
    await expect(
      exemptionService.advanceWorkflow({
        requestId: 600,
        internalStatus: "DOCUMENTATION_REQUIRED",
        note: "",
        actor: "mrivas",
      }),
    ).rejects.toThrow(/qué documentación falta/i);
  });

  it("pedir documentación no publica nada hacia M8", async () => {
    const antes = (await eventService.list({ eventType: "updateExemptionStatus" })).length;

    const solicitud = await exemptionService.advanceWorkflow({
      requestId: 600,
      internalStatus: "DOCUMENTATION_REQUIRED",
      note: "Certificado de ingresos de los últimos tres meses",
      actor: "mrivas",
    });

    expect(solicitud.internalStatus).toBe("DOCUMENTATION_REQUIRED");
    // Para el exterior sigue pendiente: el contrato no conoce este estado.
    expect(solicitud.status).toBe("REQUESTED");
    expect((await eventService.list({ eventType: "updateExemptionStatus" })).length).toBe(antes);
  });

  it("no se resuelve mientras falte la documentación pedida", async () => {
    await expect(
      exemptionService.resolve({ requestId: 600, status: "APPROVED", resolvedBy: "jlopez" }),
    ).rejects.toThrow(/falta la documentación/i);
  });

  it("registrar la documentación la deja como recibida", async () => {
    const solicitud = await exemptionService.attachDocumentation({
      requestId: 600,
      attachments: [{ name: "certificado-ingresos.pdf" }],
      actor: "mrivas",
    });

    expect(solicitud.internalStatus).toBe("DOCUMENTATION_RECEIVED");
    // El binario va a S3: la base guarda la referencia.
    expect(solicitud.attachments.at(-1)).toMatch(/^s3:\/\/rentas-documents\/exenciones\//);
  });

  it("enviar a resolución exige tener la documentación", async () => {
    await expect(
      exemptionService.advanceWorkflow({
        requestId: 602,
        internalStatus: "PENDING_RESOLUTION",
        actor: "mrivas",
      }),
    ).rejects.toThrow(/ya fue resuelta/i);
  });

  it("con la documentación, pasa a resolución", async () => {
    const solicitud = await exemptionService.advanceWorkflow({
      requestId: 600,
      internalStatus: "PENDING_RESOLUTION",
      note: "Documentación completa",
      actor: "mrivas",
    });

    expect(solicitud.internalStatus).toBe("PENDING_RESOLUTION");
    expect(solicitud.workflow.length).toBeGreaterThanOrEqual(3);
  });

  it("recién ahí la resolución viaja a M8", async () => {
    const solicitud = await exemptionService.resolve({
      requestId: 600,
      status: "APPROVED",
      resolvedBy: "jlopez",
    });

    expect(solicitud.status).toBe("APPROVED");
    expect(solicitud.internalStatus).toBe("APPROVED");

    const eventos = await eventService.list({ eventType: "updateExemptionStatus" });
    expect(eventos.some((e) => e.data?.requestId === 600 && e.data?.status === "APPROVED")).toBe(
      true,
    );
  });

  it("filtra el listado por paso del trámite", async () => {
    const enRevision = await exemptionService.list({ internalStatus: "PENDING_REVIEW" });

    expect(enRevision.every((e) => e.internalStatus === "PENDING_REVIEW")).toBe(true);
  });
});
