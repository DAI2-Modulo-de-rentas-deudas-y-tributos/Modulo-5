import { request } from "../apiClient.js";

export const securityAuditService = {
  list({ userId = "", action = "", from = "", to = "", page = 0 } = {}) {
    const params = new URLSearchParams({ entityType: "DemoUser", userId, action, from, to, page: String(page), size: "25", sort: "occurredAt,desc" });
    return request(`/api/v1/audit?${params}`);
  },
};
