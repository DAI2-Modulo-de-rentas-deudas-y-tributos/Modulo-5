import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import App from "../App.jsx";
import { PORTAL_MODULES } from "../config/portalModules.js";

/**
 * Portal del contribuyente: el ciudadano entra a su propio legajo, consulta y puede
 * iniciar dos trámites. Las pruebas verifican el recorrido y, sobre todo, que no
 * aparezcan acciones que no le corresponden.
 */
async function loginAsContribuyente(user) {
  render(<App />);
  await user.type(screen.getByLabelText(/usuario/i), "jperez");
  await user.type(screen.getByLabelText(/contraseña/i), "ciudadano123");
  await user.click(screen.getByRole("button", { name: /ingresar/i }));
  await waitFor(() => expect(screen.getByRole("heading", { name: /hola, juan/i })).toBeDefined());
}

describe("portal del contribuyente", () => {
  let user;

  beforeEach(() => {
    user = userEvent.setup();
  });

  afterEach(() => {
    cleanup();
    sessionStorage.clear();
    window.history.pushState({}, "", "/");
  });

  it("abre la portada con el resumen de la cuenta", async () => {
    await loginAsContribuyente(user);

    expect(await screen.findByText(/deuda total/i)).toBeDefined();
    expect(screen.getByText(/próximo vencimiento/i)).toBeDefined();
    // "Saldo a favor" figura como métrica y también como aviso.
    expect(screen.getAllByText(/saldo a favor/i).length).toBeGreaterThan(0);
    expect(screen.getByText(/mis obligaciones/i)).toBeDefined();
  });

  it("muestra los avisos de lo que necesita atención", async () => {
    await loginAsContribuyente(user);

    expect(await screen.findByText(/^avisos$/i)).toBeDefined();
    expect(screen.getByText(/tenés una deuda vencida|tenés \d+ deudas vencidas/i)).toBeDefined();
    expect(screen.getByText(/tenés saldo a favor/i)).toBeDefined();
  });

  it.each(PORTAL_MODULES.map((module) => [module.label, module]))(
    "abre %s desde el menú",
    async (label, module) => {
      await loginAsContribuyente(user);
      await user.click(screen.getByRole("link", { name: new RegExp(`^${label}$`, "i") }));

      const breadcrumb = await screen.findByRole("navigation", { name: /breadcrumb/i });
      expect(within(breadcrumb).getByText(module.label)).toBeDefined();
      await waitFor(() => expect(screen.queryByText(/cargando información/i)).toBeNull());
    },
  );

  it("no ofrece registrar pagos ni emitir boletas", async () => {
    await loginAsContribuyente(user);
    await user.click(screen.getByRole("link", { name: /^mis pagos$/i }));
    await screen.findByRole("navigation", { name: /breadcrumb/i });

    expect(screen.queryByRole("button", { name: /registrar pago/i })).toBeNull();
    expect(screen.queryByRole("button", { name: /emitir boleta/i })).toBeNull();
    expect(screen.queryByRole("button", { name: /reversar/i })).toBeNull();
  });

  it("no da acceso a las areas internas del municipio", async () => {
    await loginAsContribuyente(user);

    expect(screen.queryByRole("link", { name: /^liquidaciones$/i })).toBeNull();
    expect(screen.queryByRole("link", { name: /^conceptos$/i })).toBeNull();
    expect(screen.queryByRole("link", { name: /^auditoría$/i })).toBeNull();
    expect(screen.queryByRole("link", { name: /^cobros$/i })).toBeNull();
  });

  it("compara las alternativas de cuotas antes de elegir", async () => {
    await loginAsContribuyente(user);
    await user.click(screen.getByRole("link", { name: /^planes de pago$/i }));
    await user.click(await screen.findByRole("button", { name: /solicitar plan de pago/i }));

    const dialog = await screen.findByRole("dialog");
    await user.click((await within(dialog).findAllByRole("checkbox"))[0]);

    // Las tres opciones se ven a la vez, no de a una.
    const opciones = await within(dialog).findAllByRole("button", { name: /cuotas/i });
    const etiquetas = opciones.map((b) => b.textContent);
    expect(etiquetas.some((t) => t.includes("3 cuotas"))).toBe(true);
    expect(etiquetas.some((t) => t.includes("6 cuotas"))).toBe(true);
    expect(etiquetas.some((t) => t.includes("12 cuotas"))).toBe(true);

    // La opción elegida queda marcada.
    const seis = opciones.find((b) => b.textContent.includes("6 cuotas"));
    expect(seis.getAttribute("aria-pressed")).toBe("true");
    const doce = opciones.find((b) => b.textContent.includes("12 cuotas"));
    await user.click(doce);
    expect(doce.getAttribute("aria-pressed")).toBe("true");
  });

  it("descuenta el anticipo de la base financiada", async () => {
    await loginAsContribuyente(user);
    await user.click(screen.getByRole("link", { name: /^planes de pago$/i }));
    await user.click(await screen.findByRole("button", { name: /solicitar plan de pago/i }));

    const dialog = await screen.findByRole("dialog");
    await user.click((await within(dialog).findAllByRole("checkbox"))[0]);
    await user.type(within(dialog).getByLabelText(/anticipo/i), "10000");

    expect(await within(dialog).findByText(/anticipo al contado/i)).toBeDefined();
    expect(within(dialog).getByText(/importe financiado/i)).toBeDefined();
  });

  it("rechaza un anticipo que cubre toda la deuda", async () => {
    await loginAsContribuyente(user);
    await user.click(screen.getByRole("link", { name: /^planes de pago$/i }));
    await user.click(await screen.findByRole("button", { name: /solicitar plan de pago/i }));

    const dialog = await screen.findByRole("dialog");
    await user.click((await within(dialog).findAllByRole("checkbox"))[0]);
    await user.type(within(dialog).getByLabelText(/anticipo/i), "999999");
    await user.click(within(dialog).getByRole("button", { name: /enviar solicitud/i }));

    expect(await within(dialog).findByText(/tiene que ser menor a la deuda/i)).toBeDefined();
  });

  it("solicita un plan de pago viendo la cuota antes de enviarlo", async () => {
    await loginAsContribuyente(user);
    await user.click(screen.getByRole("link", { name: /^planes de pago$/i }));
    await user.click(await screen.findByRole("button", { name: /solicitar plan de pago/i }));

    const dialog = await screen.findByRole("dialog");
    await user.click((await within(dialog).findAllByRole("checkbox"))[0]);

    // La simulación aparece recién cuando hay una deuda elegida.
    expect(await within(dialog).findByText(/así quedaría tu plan/i)).toBeDefined();

    await user.click(within(dialog).getByRole("button", { name: /enviar solicitud/i }));

    expect(await screen.findByText(/solicitud enviada/i)).toBeDefined();
  });

  it("adjunta documentación a la solicitud de exención", async () => {
    await loginAsContribuyente(user);
    await user.click(screen.getByRole("link", { name: /^exenciones$/i }));
    await user.click(await screen.findByRole("button", { name: /solicitar exención/i }));

    const dialog = await screen.findByRole("dialog");
    const archivo = new File(["certificado"], "constancia-anses.pdf", {
      type: "application/pdf",
    });
    await user.upload(within(dialog).getByLabelText(/documentación respaldatoria/i), archivo);

    expect(await within(dialog).findByText("constancia-anses.pdf")).toBeDefined();

    // Se puede quitar antes de enviar.
    await user.click(within(dialog).getByRole("button", { name: /quitar constancia/i }));
    expect(within(dialog).queryByText("constancia-anses.pdf")).toBeNull();
  });

  it("abre la boleta imprimible con su código de barras", async () => {
    await loginAsContribuyente(user);
    await user.click(screen.getByRole("link", { name: /^mis boletas$/i }));

    await user.click((await screen.findAllByRole("button", { name: /ver e imprimir/i }))[0]);

    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByText(/código de barras/i)).toBeDefined();
    expect(within(dialog).getByText(/importe a pagar/i)).toBeDefined();
    expect(within(dialog).getByRole("button", { name: /^imprimir$/i })).toBeDefined();
  });

  it("solicita una exencion y valida el formulario", async () => {
    await loginAsContribuyente(user);
    await user.click(screen.getByRole("link", { name: /^exenciones$/i }));

    await user.click(await screen.findByRole("button", { name: /solicitar exención/i }));

    const dialog = await screen.findByRole("dialog");
    await user.click(within(dialog).getByRole("button", { name: /enviar solicitud/i }));

    // Sin concepto ni motivo la solicitud no sale.
    expect(await within(dialog).findByText(/elegí el concepto/i)).toBeDefined();

    await user.selectOptions(within(dialog).getByLabelText(/concepto/i), "PATENTE");
    await user.type(within(dialog).getByLabelText(/motivo/i), "Situación socioeconómica");
    await user.type(within(dialog).getByLabelText(/vigente desde/i), "2027-01-01");
    await user.type(within(dialog).getByLabelText(/vigente hasta/i), "2027-12-31");
    await user.click(within(dialog).getByRole("button", { name: /enviar solicitud/i }));

    expect(await screen.findByText(/solicitud enviada/i)).toBeDefined();
  });

  it("aclara que los pagos no se hacen desde el portal", async () => {
    await loginAsContribuyente(user);
    await user.click(screen.getByRole("link", { name: /^mis deudas$/i }));

    expect(await screen.findByText(/¿cómo pago\?/i)).toBeDefined();
  });
});
