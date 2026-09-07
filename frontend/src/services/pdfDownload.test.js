import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

describe("contrato HTTP de descarga PDF", () => {
  beforeEach(() => {
    vi.resetModules();
    vi.stubEnv("VITE_API_BASE_URL", "https://api.example.test");
    vi.stubEnv("VITE_AUTH_MODE", "mock");
    vi.stubEnv("VITE_DEV_IDENTITY_HEADERS", "true");
    sessionStorage.setItem("rentas.user", JSON.stringify({ username: "qa", role: "CONTRIBUYENTE", taxpayerId: 42 }));
    vi.stubGlobal("fetch", vi.fn());
  });

  afterEach(() => {
    sessionStorage.clear();
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
  });

  it("usa el dominio de la API, identidad del dueño y nombre del adjunto", async () => {
    fetch.mockResolvedValue(new Response("%PDF-1.4\nbody\n%%EOF", { headers: {
      "Content-Type": "application/pdf", "Content-Disposition": 'attachment; filename="BILL-42.pdf"',
    } }));
    const { request } = await import("./apiClient.js");
    const result = await request("/api/v1/bills/42/document", { responseType: "blob" });
    expect(fetch).toHaveBeenCalledWith("https://api.example.test/api/v1/bills/42/document", expect.objectContaining({
      method: "GET", headers: expect.objectContaining({ Accept: "application/pdf", "X-Dev-User": "qa",
        "X-Dev-Roles": "TAXPAYER", "X-Dev-Taxpayer-Id": "42" }),
    }));
    expect(result.filename).toBe("BILL-42.pdf");
    expect(await result.blob.text()).toContain("%PDF-1.4");
  });

  it.each([401, 403, 404, 500])("propaga el error HTTP %s sin crear un archivo", async (status) => {
    fetch.mockResolvedValue(new Response(JSON.stringify({ message: "Descarga rechazada", code: "PDF_ERROR" }),
      { status, headers: { "Content-Type": "application/json" } }));
    const { request } = await import("./apiClient.js");
    await expect(request("/api/v1/bills/42/document", { responseType: "blob" }))
      .rejects.toMatchObject({ status, message: "Descarga rechazada", code: "PDF_ERROR" });
  });

  it.each([["text/html", "<html>SPA</html>"], ["application/pdf", ""], ["application/pdf", "invalid"]])(
    "rechaza una respuesta %s con contenido inválido", async (type, body) => {
      fetch.mockResolvedValue(new Response(body, { headers: { "Content-Type": type } }));
      const { request } = await import("./apiClient.js");
      await expect(request("/api/v1/bills/42/document", { responseType: "blob" })).rejects.toThrow(/PDF/);
    },
  );

  it("funciona si CORS no expone Content-Disposition", async () => {
    fetch.mockResolvedValue(new Response("%PDF-1.4\n%%EOF", { headers: { "Content-Type": "application/pdf" } }));
    const { request } = await import("./apiClient.js");
    expect((await request("/api/v1/bills/42/document", { responseType: "blob" })).filename).toBe("boleta.pdf");
  });

  it("propaga una caída de red para permitir reintentar", async () => {
    fetch.mockRejectedValue(new TypeError("Failed to fetch"));
    const { request } = await import("./apiClient.js");
    await expect(request("/api/v1/bills/42/document", { responseType: "blob" })).rejects.toThrow("Failed to fetch");
  });
});
