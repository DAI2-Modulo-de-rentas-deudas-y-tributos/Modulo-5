import { expect, test } from "@playwright/test";
import { CIUDADANO_A, CIUDADANO_B, HOME, apiSession, login, loginAs, taxpayerId } from "./support.js";

test.describe("Acceso y permisos", () => {
  for (const username of Object.keys(HOME)) {
    test(`${username} ingresa a su área y cierra sesión`, async ({ page }) => {
      await loginAs(page, username);
      await expect(page.getByRole("heading", { level: 1 })).toBeVisible();

      await page.getByRole("button", { name: /opciones de cuenta/ }).click();
      await page.getByRole("menuitem", { name: "Cerrar sesión" }).click();
      await expect(page).toHaveURL(/\/login$/);

      // Sin sesión, la misma área vuelve a pedir el ingreso.
      await page.goto(HOME[username]);
      await expect(page).toHaveURL(/\/login$/);
    });
  }

  test("una contraseña incorrecta no ingresa y muestra el error", async ({ page }) => {
    await login(page, "rentas", "no-es-la-clave");
    await expect(page.getByText("No pudimos validar tus datos")).toBeVisible();
    await expect(page).toHaveURL(/\/login$/);
  });

  test("un contribuyente no entra al área de Rentas (BFLA)", async ({ page, request }) => {
    await loginAs(page, "contribuyente");
    await page.goto("/rentas/deudas");
    await expect(page).toHaveURL(/\/portal\/?$/);

    const headers = await apiSession(request, "contribuyente");
    expect((await request.get("/api/v1/debts", { headers })).status()).toBe(403);
  });

  test("un contribuyente no ve las deudas de otro (BOLA)", async ({ request }) => {
    const staff = await apiSession(request, "rentas");
    const own = await taxpayerId(request, staff, CIUDADANO_A.dni);
    const other = await taxpayerId(request, staff, CIUDADANO_B.dni);

    const headers = await apiSession(request, "contribuyente");
    expect((await request.get(`/api/v1/taxpayers/${own}/debts`, { headers })).status()).toBe(200);
    expect([403, 404]).toContain((await request.get(`/api/v1/taxpayers/${other}/debts`, { headers })).status());
  });
});
