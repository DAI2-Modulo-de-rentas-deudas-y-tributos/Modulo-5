import { expect, test } from "@playwright/test";
import { apiSession } from "./support.js";

// La traza queda desactivada porque el flujo usa un token DEMO real, aunque sintético.
test.use({ trace: "off" });

test("el logout local revoca en backend el token DEMO usado", async ({ page, request }) => {
  const session = await apiSession(request, "rentas");
  const oldToken = session["X-Demo-Session"];
  expect(oldToken).toBeTruthy();
  await page.addInitScript((token) => sessionStorage.setItem("rentas.token", token), oldToken);
  await page.goto("/rentas");
  await expect(page).toHaveURL(/\/rentas\/?$/);
  await page.goto("/rentas/deudas");
  await expect(page).toHaveURL(/\/rentas\/deudas$/);

  await page.getByRole("button", { name: /opciones de cuenta/ }).click();
  const logoutRequest = page.waitForRequest((request) => request.url().includes("/api/v1/dev-auth/logout"));
  const logoutResponse = page.waitForResponse((response) => response.url().includes("/api/v1/dev-auth/logout"));
  await page.getByRole("menuitem", { name: "Cerrar sesión" }).click();

  expect(Boolean((await logoutRequest).headers()["x-demo-session"])).toBe(true);
  const response = await logoutResponse;
  const error = response.status() === 204 ? null : await response.json();
  expect(response.status(), JSON.stringify({ status: response.status(), error })).toBe(204);
  await expect(page).toHaveURL(/\/login$/);
  expect(await page.evaluate(() => sessionStorage.getItem("rentas.token"))).toBeNull();
  expect((await request.get("/api/v1/dev-auth/me", { headers: { "X-Demo-Session": oldToken } })).status()).toBe(401);

  await page.reload();
  await expect(page).toHaveURL(/\/login$/);
  await page.goBack();
  await expect(page).toHaveURL(/\/login$/);
});
