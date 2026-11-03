import AxeBuilder from "@axe-core/playwright";
import { expect, test } from "@playwright/test";
import { loginAs } from "./support.js";

// Eje A: responsive a 360, 768 y 1280 px y WCAG 2.2 AA sin violaciones críticas ni serias.
const VIEWPORTS = [
  { width: 360, height: 800 },
  { width: 768, height: 1024 },
  { width: 1280, height: 800 },
];

const SCREENS = [
  { user: null, path: "/login" },
  { user: "contribuyente", path: "/portal" },
  { user: "contribuyente", path: "/portal/deudas" },
  { user: "cajero", path: "/caja/cobros" },
  { user: "rentas", path: "/rentas/deudas" },
  { user: "auditor", path: "/auditor" },
];

for (const viewport of VIEWPORTS) {
  test.describe(`${viewport.width} px`, () => {
    test.use({ viewport });

    for (const { user, path } of SCREENS) {
      test(`${path} es accesible y no desborda`, async ({ page }) => {
        if (user) await loginAs(page, user);
        await page.goto(path);
        await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
        await page.waitForLoadState("networkidle");

        const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
        expect(overflow, "la página no debe tener scroll horizontal").toBeLessThanOrEqual(0);

        const { violations } = await new AxeBuilder({ page })
          .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa"])
          .analyze();
        const blocking = violations
          .filter((violation) => ["critical", "serious"].includes(violation.impact))
          .map((violation) => `${violation.id} (${violation.impact}): ${violation.nodes.map((node) => node.target.join(" ")).join(", ")}`);
        expect(blocking).toEqual([]);
      });
    }
  });
}
