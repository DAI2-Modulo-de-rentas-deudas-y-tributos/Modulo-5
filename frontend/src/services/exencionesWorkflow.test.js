import { afterEach, describe, expect, it, vi } from "vitest";
import { exemptionService } from "./rentasService.js";
import { instalarBackendFalso, pagina } from "../__tests__/fixtures/backendFalso.js";

/**
 * Trámite de exención. El backend gobierna el estado; el cliente traduce cada paso
 * del trámite a la operación que le corresponde y frena los pedidos que no puede
 * expresar, en vez de mandar algo que el backend va a rechazar.
 */

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("pasos del trámite", () => {
  it("cada paso llama a su propia operación", async () => {
    const casos = [
      ["PENDING_REVIEW", "start-review"],
      ["DOCUMENTATION_REQUIRED", "request-documentation"],
      ["PENDING_RESOLUTION", "submit-resolution"],
    ];

    for (const [internalStatus, accion] of casos) {
      const backend = instalarBackendFalso({
        [`POST /api/v1/exemption-requests/{id}/${accion}`]: { id: 600, status: "PENDING" },
      });

      await exemptionService.advanceWorkflow({ requestId: 600, internalStatus, note: "nota", actor: "mrivas" });

      expect(backend.llamadas[0].ruta).toBe(`/api/v1/exemption-requests/600/${accion}`);
      vi.unstubAllGlobals();
    }
  });

  it("pedir documentación viaja con el detalle de lo que falta", async () => {
    const backend = instalarBackendFalso({
      "POST /api/v1/exemption-requests/{id}/request-documentation": { id: 600 },
    });

    await exemptionService.advanceWorkflow({
      requestId: 600,
      internalStatus: "DOCUMENTATION_REQUIRED",
      note: "Falta el certificado de ingresos",
      actor: "mrivas",
    });

    expect(backend.llamadas[0].cuerpo).toEqual({ message: "Falta el certificado de ingresos" });
  });

  it("un paso que no tiene operación propia se rechaza antes de salir", async () => {
    const backend = instalarBackendFalso();

    await expect(
      exemptionService.advanceWorkflow({ requestId: 600, internalStatus: "DOCUMENTATION_RECEIVED", actor: "mrivas" }),
    ).rejects.toMatchObject({ status: 409 });
    expect(backend).not.toHaveBeenCalled();
  });
});

describe("documentación", () => {
  it("exige al menos un archivo antes de llamar al backend", async () => {
    const backend = instalarBackendFalso();

    await expect(
      exemptionService.attachDocumentation({ requestId: 600, attachments: [], actor: "mrivas" }),
    ).rejects.toMatchObject({ status: 400 });
    expect(backend).not.toHaveBeenCalled();
  });

  it("registra una referencia por archivo, nunca el binario", async () => {
    const backend = instalarBackendFalso({
      "POST /api/v1/exemption-requests/{id}/documentation": { id: 1 },
    });

    await exemptionService.attachDocumentation({
      requestId: 600,
      attachments: [
        { name: "certificado.pdf", type: "INCOME_PROOF", externalDocumentId: "s3://docs/certificado.pdf" },
        { name: "dni.jpg", type: "ID" },
      ],
      actor: "mrivas",
    });

    expect(backend.llamadas).toHaveLength(2);
    expect(backend.llamadas[0].cuerpo).toEqual({
      externalDocumentId: "s3://docs/certificado.pdf",
      documentType: "INCOME_PROOF",
      fileName: "certificado.pdf",
    });
    // Sin id externo cae al nombre: lo que viaja es una referencia, no el archivo.
    expect(backend.llamadas[1].cuerpo.externalDocumentId).toBe("dni.jpg");
  });
});

describe("listado", () => {
  it("filtra por paso del trámite", async () => {
    const backend = instalarBackendFalso({
      "GET /api/v1/exemption-requests": pagina([{ id: 600, taxConceptId: 1, status: "PENDING" }]),
    });

    await exemptionService.list({ internalStatus: "PENDING_REVIEW" });

    expect(backend.llamadas[0].ruta).toContain("/api/v1/exemption-requests");
  });
});
