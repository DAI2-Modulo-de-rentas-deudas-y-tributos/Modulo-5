import { request } from "../apiClient.js";

export const taxpayerService = {
  async search({ query = "", type = "" } = {}) {
    const params = new URLSearchParams({ query, type });
    return request(`/api/v1/taxpayers?${params}`);
  },

  async getById(id) {
    return request(`/api/v1/taxpayers/${id}`);
  },
};
