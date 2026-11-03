import { defineConfig, devices } from "@playwright/test";

// Escenarios de punta a punta contra el sistema levantado con `docker compose up -d --build`.
// Los datos los carga `demo-setup`; ver frontend/e2e/README.md.
export default defineConfig({
  testDir: "./e2e",
  testMatch: "**/*.e2e.js",
  timeout: 30_000,
  expect: { timeout: 10_000 },
  // Sin reintentos: un escenario que pasa sólo al segundo intento es inestable y tiene que fallar.
  retries: 0,
  // Comparten los datos de demostración (cobros, vencimientos): en serie, sin interferencias.
  workers: 1,
  reporter: [["list"], ["html", { open: "never", outputFolder: "playwright-report" }]],
  outputDir: "test-results",
  use: {
    baseURL: process.env.E2E_BASE_URL ?? "http://localhost:5173",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
});
