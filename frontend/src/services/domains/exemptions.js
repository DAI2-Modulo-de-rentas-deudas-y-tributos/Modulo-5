import { ApiError, request } from "../apiClient.js";
import { taxpayerService } from "./taxpayers.js";
import { apiConceptByCode } from "./taxConfigurations.js";

export const exemptionService = {
  async list({ status = "", internalStatus = "" } = {}) {
    const params = new URLSearchParams({ status, size: "100" });
    return request(`/api/v1/exemption-requests?${params}`);
  },

  /** Ficha completa: solicitud, documentación, evaluación previa y resolución. */
  async detail(requestId) {
    const exemption = await request(`/api/v1/exemption-requests/${requestId}`);
    const [documents, timeline, taxpayer, concept] = await Promise.all([
      request(`/api/v1/exemption-requests/${requestId}/documents`),
      request(`/api/v1/exemption-requests/${requestId}/history`),
      taxpayerService.getById(exemption.citizenId),
      request(`/api/v1/tax-concepts/${exemption.conceptId}`),
    ]);
    const result = timeline.result ?? null;
    const history = (timeline.entries ?? []).map((entry) => ({
      ...entry,
      at: entry.date,
      note:
        entry.message ??
        (entry.document?.fileName ? `Documento: ${entry.document.fileName}` : null),
    }));
    return {
      ...exemption,
      taxpayer,
      taxpayerName: taxpayer.name,
      concept,
      conceptCode: concept.code,
      conceptName: concept.name,
      documents: documents ?? [],
      attachments: documents ?? [],
      history,
      result,
      percentage: result?.percentage ?? null,
      validFrom: result?.validFrom ?? null,
      validUntil: result?.validUntil ?? null,
      approvedAt: result?.approvedAt ?? null,
      resolutionMessage: result?.message ?? exemption.resolutionReason ?? null,
    };
  },

  /**
   * Estado interno del trámite. M8 sólo conoce APPROVED y REJECTED: los pasos de
   * documentación y revisión son de M5 y no se publican.
   */
  async advanceWorkflow({ requestId, internalStatus, note, actor }) {
    const action = { PENDING_REVIEW: "start-review", DOCUMENTATION_REQUIRED: "request-documentation", PENDING_RESOLUTION: "submit-resolution" }[internalStatus];
    if (!action) throw new ApiError("Ese paso requiere adjuntar documentación mediante su operación específica.", 409);
    return request(`/api/v1/exemption-requests/${requestId}/${action}`, { method: "POST", body: action === "request-documentation" ? { message: note } : action === "submit-resolution" ? { observation: note ?? null } : undefined });
  },

  /** Registra una referencia a documentación ya almacenada fuera de M5. */
  async attachDocumentation({ requestId, attachments, actor }) {
    if (!attachments?.length) throw new ApiError("Indicá al menos un documento.", 400);
    if (attachments.some((file) => !file.externalDocumentId || !file.type)) {
      throw new ApiError("Cada documento necesita un identificador externo y un tipo; M5 no almacena archivos.", 400);
    }
    let result;
    for (const file of attachments) {
      result = await request(`/api/v1/exemption-requests/${requestId}/documentation`, { method: "POST", body: { externalDocumentId: file.externalDocumentId, documentType: file.type, fileName: file.name ?? null } });
    }
    return result;
  },

  /** Alta de solicitud por mesa de entradas → publica exemptionRequested hacia M8. */
  async requestExemption({
    citizenId,
    conceptCode,
    reason,
    requestedPercentage,
    requestedFrom,
    requestedUntil,
    attachments = [],
  }) {
    const concept = await apiConceptByCode(conceptCode);
    if (attachments.some((file) => !file.externalDocumentId || !file.type)) {
      throw new ApiError("Cada documento necesita un identificador externo y un tipo; M5 no almacena archivos.", 400);
    }
    const created = await request("/api/v1/exemption-requests", {
      method: "POST",
      body: {
        taxpayerId: citizenId,
        taxConceptId: concept.id,
        reason,
        percentage: requestedPercentage,
        validFrom: requestedFrom,
        validUntil: requestedUntil,
        documents: attachments.map((file) => ({ externalDocumentId: file.externalDocumentId, documentType: file.type, fileName: file.name ?? null })),
      },
    });
    return created;
  },

  /** ResolveExemptionRequest → publica updateExemptionStatus (APPROVED | REJECTED). */
  async resolve({ requestId, status, percentage, validFrom, validUntil, reason, resolvedBy }) {
    const action = status === "REJECTED" ? "reject" : "approve";
    const result = await request(`/api/v1/exemption-requests/${requestId}/${action}`, { method: "POST", body: action === "reject" ? { reason } : { percentage, validFrom, validUntil, observation: reason ?? null } });
    return action === "approve"
      ? { ...result, exemptionId: result.id, status: "APPROVED" }
      : result;
  },
};
