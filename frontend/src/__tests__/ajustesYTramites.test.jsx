import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import App from "../App.jsx";

/** Ajustes manuales, saldos a favor y el trámite de documentación, desde la pantalla. */
async function entrar(user, usuario, modulo) {
  render(<App />);
  await user.type(screen.getByLabelText(/usuario/i), usuario);
  await user.type(screen.getByLabelText(/contraseña/i), "rentas123");
  await user.click(screen.getByRole("button", { name: /ingresar/i }));
  await waitFor(() => expect(screen.getByRole("heading", { name: /hola,/i })).toBeDefined());
  await user.click(screen.getByRole("link", { name: modulo }));
  await waitFor(() => expect(screen.queryByText(/cargando información/i)).toBeNull());
}

describe("ajustes y saldos a favor", () => {
  let user;
  beforeEach(() => { user = userEvent.setup(); });
  afterEach(() => {
    cleanup();
    sessionStorage.clear();
    window.history.pushState({}, "", "/");
  });

  it("aclara que autorizar y ejecutar son actos distintos", async () => {
    await entrar(user, "mrivas", /ajustes y saldos/i);

    expect(await screen.findByText(/autorizar y ejecutar son actos distintos/i)).toBeDefined();
  });

  it("muestra el saldo a favor disponible", async () => {
    await entrar(user, "mrivas", /ajustes y saldos/i);

    expect(await screen.findByText(/saldos a favor/i)).toBeDefined();
    expect(screen.getByText(/aplicar a una deuda/i)).toBeDefined();
  });

  it("aplicar un saldo aclara que no genera un pago", async () => {
    await entrar(user, "mrivas", /ajustes y saldos/i);
    await user.click(await screen.findByRole("button", { name: /aplicar a una deuda/i }));

    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByText(/no se registra un pago nuevo/i)).toBeDefined();
  });

  it("aplica el saldo y reduce la deuda", async () => {
    await entrar(user, "mrivas", /ajustes y saldos/i);
    await user.click(await screen.findByRole("button", { name: /aplicar a una deuda/i }));

    const dialog = await screen.findByRole("dialog");
    const select = within(dialog).getByLabelText(/deuda/i);
    await waitFor(() =>
      expect(within(select).getAllByRole("option").length).toBeGreaterThan(1),
    );
    await user.selectOptions(select, within(select).getAllByRole("option")[1]);
    await user.type(within(dialog).getByLabelText(/importe a aplicar/i), "5000");
    await user.click(within(dialog).getByRole("button", { name: /^aplicar$/i }));

    expect(await screen.findByText(/saldo aplicado/i)).toBeDefined();
  });

  it("el ajuste exige motivo y algún cambio", async () => {
    await entrar(user, "mrivas", /ajustes y saldos/i);
    await user.click(await screen.findByRole("button", { name: /proponer ajuste/i }));

    const dialog = await screen.findByRole("dialog");
    await user.click(within(dialog).getByRole("button", { name: /^proponer$/i }));

    expect(await within(dialog).findByText(/elegí la deuda/i)).toBeDefined();
  });

  it("el analista propone y no puede autorizar", async () => {
    await entrar(user, "mrivas", /ajustes y saldos/i);
    await user.click(await screen.findByRole("button", { name: /proponer ajuste/i }));

    const dialog = await screen.findByRole("dialog");
    const select = within(dialog).getByLabelText(/^deuda/i);
    await waitFor(() =>
      expect(within(select).getAllByRole("option").length).toBeGreaterThan(1),
    );
    await user.selectOptions(select, within(select).getAllByRole("option")[1]);
    await user.clear(within(dialog).getByLabelText(/importe ajustado/i));
    await user.type(within(dialog).getByLabelText(/importe ajustado/i), "1000");
    await user.type(within(dialog).getByLabelText(/motivo del ajuste/i), "Error de carga");
    await user.click(within(dialog).getByRole("button", { name: /^proponer$/i }));

    expect(await screen.findByText(/ajuste propuesto/i)).toBeDefined();
    expect(await screen.findByText(/espera al supervisor/i)).toBeDefined();
  });

  it("el Supervisor autoriza, y recién ahí aparece Ejecutar", async () => {
    await entrar(user, "jlopez", /ajustes y saldos/i);

    await user.click(await screen.findByRole("button", { name: /autorizar/i }));
    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByText(/autorizar no aplica el cambio/i)).toBeDefined();
    await user.click(within(dialog).getByRole("button", { name: /confirmar/i }));

    expect(await screen.findByText(/ajuste autorizado/i)).toBeDefined();
    expect(await screen.findByRole("button", { name: /ejecutar/i })).toBeDefined();
  });
});

describe("trámite de documentación en exenciones", () => {
  let user;
  beforeEach(() => { user = userEvent.setup(); });
  afterEach(() => {
    cleanup();
    sessionStorage.clear();
    window.history.pushState({}, "", "/");
  });

  it("aclara que los pasos no salen de Rentas", async () => {
    await entrar(user, "mrivas", /exenciones/i);
    await user.click((await screen.findAllByRole("button", { name: /trámite/i }))[0]);

    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByText(/estos pasos no salen de rentas/i)).toBeDefined();
  });

  it("pedir documentación exige decir cuál falta", async () => {
    await entrar(user, "mrivas", /exenciones/i);
    await user.click((await screen.findAllByRole("button", { name: /trámite/i }))[0]);

    const dialog = await screen.findByRole("dialog");
    await user.click(within(dialog).getByRole("button", { name: /confirmar/i }));

    expect(await within(dialog).findByText(/indicá qué documentación falta/i)).toBeDefined();
  });

  it("pide documentación y la solicitud queda marcada", async () => {
    await entrar(user, "mrivas", /exenciones/i);
    await user.click((await screen.findAllByRole("button", { name: /trámite/i }))[0]);

    const dialog = await screen.findByRole("dialog");
    await user.type(
      within(dialog).getByLabelText(/qué documentación falta/i),
      "Certificado de ingresos",
    );
    await user.click(within(dialog).getByRole("button", { name: /confirmar/i }));

    expect(await screen.findByText(/trámite actualizado/i)).toBeDefined();
    expect(await screen.findByText(/falta documentación/i)).toBeDefined();
  });
});

describe("información recibida en tickets", () => {
  let user;
  beforeEach(() => { user = userEvent.setup(); });
  afterEach(() => {
    cleanup();
    sessionStorage.clear();
    window.history.pushState({}, "", "/");
  });

  it("marca en el listado los tickets con documentación adjunta", async () => {
    await entrar(user, "mrivas", /tickets/i);

    expect(await screen.findByText(/1 adjunto/i)).toBeDefined();
  });

  it("muestra la información y los archivos que mandó el ciudadano", async () => {
    await entrar(user, "mrivas", /tickets/i);
    await user.click((await screen.findAllByRole("button", { name: /cambiar estado/i }))[0]);

    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByText(/información recibida del ciudadano/i)).toBeDefined();
    expect(within(dialog).getByText(/comprobante-pago\.pdf/i)).toBeDefined();
  });
});
