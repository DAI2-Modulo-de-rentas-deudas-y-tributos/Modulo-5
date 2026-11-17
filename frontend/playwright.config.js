import { defineConfig, devices } from "@playwright/test";

const projects = [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }];
if (process.env.E2E_WEBKIT === "true") {
  projects.push(
    { name: "webkit-logout", testMatch: "**/logout-menu.e2e.js", grep: /@webkit/, use: { ...devices["Desktop Safari"] } },
    { name: "webkit-touch-logout", testMatch: "**/logout-menu.e2e.js", grep: /@touch/, use: { ...devices["iPhone 13"] } },
  );
}

// Escenarios de punta a punta contra el sistema levantado con `docker compose up -d --build`,
// con los datos que carga `demo-setup`. Se corren con scripts/compose/e2e-test.sh.
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
  // WebKit es focalizado y opt-in local para no exigir instalar otro browser en el CI habitual.
  projects,
});
