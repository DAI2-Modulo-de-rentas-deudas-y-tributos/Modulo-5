import { allPages } from "./apiCollection.js";
import { ApiError, request } from "./apiClient.js";

const OUTBOX = "/api/v1/integrations/outbox";
const detailPath = (id) => `${OUTBOX}/${encodeURIComponent(id)}`;

export const integrationOutboxService = {
  async list() {
    const rows = await allPages(`${OUTBOX}?sort=createdAt,desc&sort=id,desc`);
    return [...new Map(rows.map((event) => [event.id, event])).values()];
  },

  async prepareRetry(event) {
    const { data, headers } = await request(detailPath(event.id), { includeHeaders: true });
    if (data.status !== event.status || data.retryCount !== event.retryCount
      || data.lastAttemptAt !== event.lastAttemptAt || data.targetModule !== event.targetModule) {
      throw new ApiError("El evento cambió. Revisá su estado actualizado antes de reintentar.", 412);
    }
    const etag = headers.get("ETag");
    if (!etag) throw new Error("No se pudo verificar la versión del evento.");
    return { etag, idempotencyKey: crypto.randomUUID() };
  },

  retry(id, { etag, idempotencyKey }) {
    return request(`${detailPath(id)}/retry`, {
      method: "POST",
      headers: { "If-Match": etag, "Idempotency-Key": idempotencyKey },
    });
  },
};
