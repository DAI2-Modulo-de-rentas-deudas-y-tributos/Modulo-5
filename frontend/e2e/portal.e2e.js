import { expect, test } from "@playwright/test";
import { loginAs } from "./support.js";

test("el contribuyente consulta sus deudas y su boleta", async ({ page }) => {
  await loginAs(page, "contribuyente");

  await page.goto("/portal/deudas");
  const debts = page.getByRole("table");
  await expect(debts.getByRole("row", { name: /Alumbrado, barrido y limpieza/ }).first()).toBeVisible();
  await expect(debts.getByRole("row", { name: /Tasa de servicios generales/ })).toBeVisible();

  await page.goto("/portal/boletas");
  await expect(page.getByRole("table").getByRole("row", { name: /Emitida/ }).first()).toBeVisible();
});
