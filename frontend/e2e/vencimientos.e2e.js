import { expect, test } from "@playwright/test";
import { CIUDADANO_A, loginAs } from "./support.js";

test("Rentas procesa los vencimientos y la deuda atrasada queda vencida", async ({ page }) => {
  await loginAs(page, "rentas");
  await page.goto("/rentas/deudas");
  await page.getByRole("button", { name: "Procesar vencimientos" }).click();
  await expect(page.getByText("Vencimientos procesados")).toBeVisible();

  await page.getByRole("combobox", { name: "Estado" }).selectOption({ label: "Vencida" });
  await expect(page.getByRole("row", { name: new RegExp(`${CIUDADANO_A.name}.*Vencida`) }).first()).toBeVisible();
});
