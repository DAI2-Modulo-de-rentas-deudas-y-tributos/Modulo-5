import { ApiError, request } from "./apiClient.js";

/** Completa colecciones paginadas sin presentar una consulta parcial como completa. */
export async function allPages(path) {
  const url = new URL(path, "https://rentas.local");
  url.searchParams.set("size", "100");
  const rows = [];
  for (let page = 0; ; page += 1) {
    url.searchParams.set("page", String(page));
    const result = await request(`${url.pathname}${url.search}`);
    if (!Array.isArray(result)) throw new ApiError("El servidor devolvió una colección inválida.", 502);
    rows.push(...result);
    if (!result.page) return rows;
    if (result.page.number !== page || (page > 0 && result.length === 0)) {
      throw new ApiError("No se pudo completar la consulta paginada.", 502);
    }
    if (result.page.last || page + 1 >= result.page.totalPages) return rows;
    if (result.length === 0) throw new ApiError("No se pudo completar la consulta paginada.", 502);
  }
}
