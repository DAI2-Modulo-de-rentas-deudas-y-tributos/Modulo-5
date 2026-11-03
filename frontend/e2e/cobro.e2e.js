import { expect, test } from "@playwright/test";
import { CIUDADANO_A, loginAs } from "./support.js";

test("la caja registra un pago parcial y el contribuyente lo ve en su cuenta", async ({ page }) => {
  await loginAs(page, "cajero");
  await page.goto("/caja/cobros");
  await page.getByRole("searchbox", { name: "DNI / CUIT / N° de boleta / N° de deuda" }).fill(CIUDADANO_A.dni);
  await page.getByRole("button", { name: "Buscar" }).click();
  await page.getByRole("row", { name: new RegExp(CIUDADANO_A.name) }).click();

  const debt = page.getByRole("combobox", { name: "Deuda a cobrar" });
  await expect(debt.getByRole("option")).not.toHaveCount(1);
  await debt.selectOption({ index: 1 });
  await page.getByRole("combobox", { name: "Medio de pago" }).selectOption({ label: "Efectivo" });
  await page.getByRole("spinbutton", { name: "Importe a cobrar" }).fill("101.37");
  await page.getByRole("button", { name: "Registrar pago" }).click();
  await expect(page.getByText("Pago registrado correctamente")).toBeVisible();

  await loginAs(page, "contribuyente");
  await page.goto("/portal/pagos");
  await expect(page.getByText("$ 101,37").first()).toBeVisible();
});
