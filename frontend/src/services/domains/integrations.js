import { request } from "../apiClient.js";
import { allPages } from "./shared.js";

export const outboxService = {
  list() { return allPages("/api/v1/integrations/outbox?sort=createdAt,desc"); },
};

export const eventService = {
  async list({ direction = "", status = "", eventType = "" } = {}) {
    const params = new URLSearchParams({ direction, status, eventType });
    return request(`/api/v1/events?${params}`);
  },

  /** Reproceso manual de un evento en DLQ (el consumidor es idempotente vía eventId). */
  async retry(eventId) {
    return request(`/api/v1/events/${eventId}/retry`, { method: "POST" });
  },
};

export const externalObligationService = {
  async list({ externalReferenceId = "", status = "", sourceModule = "" } = {}) {
    const params = new URLSearchParams({ externalReferenceId, status, sourceModule, size: "100", sort: "receivedAt,desc" });
    return request(`/api/v1/external-obligations?${params}`);
  },

  async errors() {
    return request("/api/v1/external-obligations/errors?size=100&sort=receivedAt,desc");
  },

  async detail(id) {
    return request(`/api/v1/external-obligations/${id}`);
  },

  async retry(id) {
    return request(`/api/v1/external-obligations/${id}/retry`, { method: "POST" });
  },
};

export const ticketService = {
  async list({ status = "", priority = "" } = {}) {
    const params = new URLSearchParams({ status, priority });
    return request(`/api/v1/tickets?${params}`);
  },

  /** Usa los casos de uso reales de M5; cada operación deja su evento en el outbox local. */
  async updateStatus({ ticketId, status, message, takeOwnership = false }) {
    const base = `/api/v1/tickets/${ticketId}`;
    if (status === "IN_PROGRESS") return request(`${base}/assign`, { method: "POST" });
    if (takeOwnership) await request(`${base}/assign`, { method: "POST" });
    if (status === "WAITING_FOR_INFORMATION") {
      return request(`${base}/request-information`, { method: "POST", body: { message } });
    }
    if (status === "COMPLETED") {
      return request(`${base}/complete`, { method: "POST", body: { resolution: message } });
    }
    if (status === "REJECTED") {
      return request(`${base}/reject`, { method: "POST", body: { reason: message } });
    }
    throw new Error("Estado de ticket no soportado.");
  },
};

