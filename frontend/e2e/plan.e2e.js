import { expect, test } from "@playwright/test";
import { CIUDADANO_A, loginAs } from "./support.js";

test("el contribuyente pide un plan de pagos y Rentas lo recibe pendiente", async ({ page }) => {
  await loginAs(page, "contribuyente");
  await page.goto("/portal/planes");
  await page.getByRole("button", { name: "Solicitar plan de pago" }).click();

  const dialog = page.getByRole("dialog", { name: "Solicitar plan de pago" });
  await dialog.getByRole("checkbox", { name: /Tasa de servicios generales/ }).check();
  await dialog.getByRole("button", { name: /^3 cuotas/ }).click();
  await dialog.getByRole("button", { name: "Enviar solicitud" }).click();
  await expect(dialog).toBeHidden();
  await expect(page.getByText("Sin solicitudes")).toBeHidden();

  await loginAs(page, "rentas");
  await page.goto("/rentas/planes");
  await page.getByRole("combobox", { name: "Estado" }).selectOption({ label: "Pendientes" });
  await expect(page.getByRole("row", { name: new RegExp(CIUDADANO_A.name) }).first()).toBeVisible();
});
