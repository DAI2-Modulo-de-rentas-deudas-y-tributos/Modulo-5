import { allPages } from "./apiCollection.js";

export const integrationOutboxService = {
  async list() {
    const rows = await allPages("/api/v1/integrations/outbox?sort=createdAt,desc&sort=id,desc");
    return [...new Map(rows.map((event) => [event.id, event])).values()];
  },
};
