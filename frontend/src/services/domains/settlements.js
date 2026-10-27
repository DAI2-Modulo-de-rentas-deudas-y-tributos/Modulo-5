import { apiConceptByCode } from "./taxConfigurations.js";
import { ApiError, request } from "../apiClient.js";
import { taxpayerService } from "./taxpayers.js";

export const settlementService = {
  async preview({ taxpayerId, conceptCode, period, baseAmount, dueDate }) {
    const concept = await apiConceptByCode(conceptCode);
    const preview = await request("/api/v1/liquidations/preview", {
      method: "POST", body: { taxpayerId: Number(taxpayerId), taxConceptId: concept.id, period, taxableBase: Number(baseAmount), dueDate },
    });
    const configuration = await request(`/api/v1/tax-configurations/${preview.taxConfigurationId}`);
    return { ...preview, configuration };
  },
  async list({ period = "", conceptCode = "", status = "", taxpayerId = "" } = {}) {
    const params = new URLSearchParams({ period, status, taxpayerId });
    if (conceptCode) params.set("conceptId", String((await apiConceptByCode(conceptCode)).id));
    return request(`/api/v1/settlements?${params}`);
  },

  /** Emite la liquidación individual y crea su deuda en la misma operación. */
  async generate({ taxpayerId, conceptCode, period, baseAmount, dueDate }) {
    const concept = await apiConceptByCode(conceptCode);
    return request("/api/v1/settlements", {
      method: "POST",
      body: { taxpayerId, conceptId: concept.id, period, baseAmount, dueDate },
    });
  },

  /**
   * Previsualiza un lote antes de emitirlo (PreviewSettlementBatchRequest).
   *
   * Devuelve tres grupos porque no todos los casos son iguales:
   *  - `items`    : lo que se va a generar, con el descuento ya calculado por contribuyente.
   *  - `errors`   : lo que queda afuera y por qué. Nada de esto se genera.
   *  - `warnings` : se genera igual, pero el operador tiene que saberlo.
   *
   * Un contribuyente bloqueado o fallecido **no** es un error: la obligación existe
   * igual. Lo que M1 restringe es la emisión de boletas, no la liquidación.
   */
  async previewBatch({ conceptCode, period, baseAmount, dueDate, taxpayerType = "" }) {
    const [concept, taxpayers] = await Promise.all([
      apiConceptByCode(conceptCode),
      taxpayerService.search({ type: taxpayerType }),
    ]);
    const run = await request("/api/v1/liquidation-runs", {
      method: "POST",
      body: { taxConceptId: concept.id, period, dueDate, items: taxpayers.map((item) => ({ taxpayerId: item.id, taxableBase: Number(baseAmount) })) },
    });
    const preview = await request(`/api/v1/liquidation-runs/${run.id}/preview`, { method: "POST" });
    // El ítem de la corrida sólo trae taxpayerId y errorMessage: el nombre lo resuelve
    // el padrón que ya se consultó para armarla.
    const nombreDe = (taxpayerId) => taxpayers.find((t) => t.id === taxpayerId)?.name ?? `Contribuyente #${taxpayerId}`;
    const filas = (preview.items ?? []).map((item) => ({
      ...item,
      taxpayerName: nombreDe(item.taxpayerId),
      amount: item.previewAmount,
      reason: item.errorMessage ?? item.errorCode ?? null,
    }));
    const items = filas.filter((item) => item.status !== "ERROR");
    const errors = filas.filter((item) => item.status === "ERROR");
    return { ...preview, items, errors, totals: { toGenerate: items.length, skipped: errors.length, amount: preview.run?.estimatedTotalAmount ?? 0 } };
  },

  /** Envía la corrida ya previsualizada; aprobación y ejecución siguen separadas. */
  async submitBatch(preview) {
    if (!preview?.run?.id) throw new ApiError("Previsualizá la corrida antes de enviarla.", 400);
    const run = await request(`/api/v1/liquidation-runs/${preview.run.id}/submit`, { method: "POST" });
    return { run, submitted: preview.items.length, totals: preview.totals, errors: preview.errors };
  },
};

export const liquidationRunService = {
  async list({ status = "" } = {}) {
    const params = new URLSearchParams({ status, size: "100", sort: "createdAt,desc" });
    return request(`/api/v1/liquidation-runs?${params}`);
  },

  async detail(id) {
    return request(`/api/v1/liquidation-runs/${id}`);
  },

  async submit(id) {
    return request(`/api/v1/liquidation-runs/${id}/submit`, { method: "POST" });
  },

  async resolve({ id, status, reason = "" }) {
    const action = status === "REJECTED" ? "reject" : "approve";
    return request(`/api/v1/liquidation-runs/${id}/${action}`, {
      method: "POST",
      body: action === "reject" ? { reason } : { observation: reason || null },
    });
  },

  async execute(id) {
    return request(`/api/v1/liquidation-runs/${id}/execute`, { method: "POST" });
  },
};

