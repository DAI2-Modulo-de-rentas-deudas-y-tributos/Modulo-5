import { expect, test } from "@playwright/test";

const fixtureToken = "synthetic-playwright-session";
const fixtureUser = {
  id: 1,
  username: "fixture.rentas",
  displayName: "Usuario sintético",
  role: "RENTAS",
  authorities: ["RENTAS"],
  taxpayerId: null,
  active: true,
};

async function openRentas(page) {
  await page.addInitScript(({ token, user }) => {
    sessionStorage.setItem("rentas.token", token);
    sessionStorage.setItem("rentas.user", JSON.stringify(user));
  }, { token: fixtureToken, user: fixtureUser });
  await page.route("**/api/**", (route) => route.fulfill({ status: 200, contentType: "application/json", body: "{}" }));
  await page.route("**/api/v1/dev-auth/me", (route) => route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify(fixtureUser),
  }));
  await page.route("**/api/v1/dev-auth/logout", (route) => route.fulfill({ status: 204 }));
  await page.goto("/rentas");
  await expect(page.getByRole("button", { name: /opciones de cuenta/ })).toBeVisible();
}

test("el click de logout sobrevive a blur con relatedTarget null @webkit", async ({ page }) => {
  await openRentas(page);

  const trigger = page.getByRole("button", { name: /opciones de cuenta/ });
  await trigger.click();
  await expect(page.getByRole("menuitem", { name: "Cerrar sesión" })).toBeVisible();
  await trigger.evaluate((element) => element.dispatchEvent(new FocusEvent("focusout", { bubbles: true, relatedTarget: null })));

  await expect(page.getByRole("menuitem", { name: "Cerrar sesión" })).toBeVisible();
  const consoleTypes = [];
  page.on("console", (message) => consoleTypes.push(message.type()));
  const logout = page.waitForResponse((response) => response.url().includes("/api/v1/dev-auth/logout"));
  await page.getByRole("menuitem", { name: "Cerrar sesión" }).click();
  expect((await logout).status()).toBe(204);
  await expect(page).toHaveURL(/\/login$/);
  expect(await page.evaluate(() => sessionStorage.getItem("rentas.token"))).toBeNull();
  expect(consoleTypes).not.toContain("error");
});

test("el menú se cierra al sacar el foco con Tab @webkit", async ({ page }, testInfo) => {
  test.skip(testInfo.project.use.hasTouch, "El proyecto touch valida el tap.");
  await openRentas(page);
  await page.getByRole("button", { name: /opciones de cuenta/ }).click();
  await page.keyboard.press("Tab");
  await expect(page.getByRole("menu")).toHaveCount(0);
});

test("Escape cierra el menú y devuelve el foco al trigger @webkit", async ({ page }, testInfo) => {
  test.skip(testInfo.project.use.hasTouch, "La interacción de teclado se valida en escritorio.");
  await openRentas(page);
  const trigger = page.getByRole("button", { name: /opciones de cuenta/ });
  await trigger.click();
  await expect(page.getByRole("menuitem", { name: "Cerrar sesión" })).toBeFocused();
  await page.keyboard.press("Escape");
  await expect(page.getByRole("menu")).toHaveCount(0);
  await expect(trigger).toBeFocused();
});

test("el clic fuera cierra el menú @webkit", async ({ page }, testInfo) => {
  test.skip(testInfo.project.use.hasTouch, "El proyecto touch valida el tap del item.");
  await openRentas(page);
  await page.getByRole("button", { name: /opciones de cuenta/ }).click();
  await page.getByRole("heading", { level: 1 }).click();
  await expect(page.getByRole("menu")).toHaveCount(0);
});

test("el teclado activa Cerrar sesión @webkit", async ({ page }, testInfo) => {
  test.skip(testInfo.project.use.hasTouch, "La activación por teclado se valida en escritorio.");
  await openRentas(page);
  const trigger = page.getByRole("button", { name: /opciones de cuenta/ });
  await trigger.focus();
  await page.keyboard.press("Enter");
  const item = page.getByRole("menuitem", { name: "Cerrar sesión" });
  await expect(item).toBeFocused();
  await page.keyboard.press("Enter");
  await expect(page).toHaveURL(/\/login$/);
  expect(await page.evaluate(() => sessionStorage.getItem("rentas.token"))).toBeNull();
});

test("un tap no desmonta Cerrar sesión antes de activar el item @touch", async ({ page }, testInfo) => {
  test.skip(!testInfo.project.use.hasTouch, "Requiere el proyecto Playwright con touch habilitado.");
  await openRentas(page);
  const trigger = page.getByRole("button", { name: /opciones de cuenta/ });
  await trigger.tap();
  const item = page.getByRole("menuitem", { name: "Cerrar sesión" });
  await expect(item).toBeVisible();
  await trigger.evaluate((element) => element.dispatchEvent(new FocusEvent("focusout", { bubbles: true, relatedTarget: null })));
  await expect(item).toBeVisible();
  await item.tap();
  await expect(page).toHaveURL(/\/login$/);
  expect(await page.evaluate(() => sessionStorage.getItem("rentas.token"))).toBeNull();
});
