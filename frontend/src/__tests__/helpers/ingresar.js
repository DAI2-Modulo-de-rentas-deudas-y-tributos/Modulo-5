import { screen } from "@testing-library/react";
import { WORKSPACES } from "../../config/workspaces.js";

/** El área de cada cuenta de agente del mock, para elegir su emblema en el riel. */
const AREA_POR_USUARIO = {
  mrivas: "PERSONAL",
  jlopez: "SUPERVISOR",
  pcabrera: "CAJERO",
  acastro: "AUDITOR",
};

/**
 * Ingresa por la puerta de agentes municipales.
 *
 * El login abre en el acceso ciudadano, así que hay que cruzar de puerta y elegir el
 * área antes de confirmar: entrar por el área equivocada no abre sesión. Vive acá y no
 * repetido en cada archivo para que el próximo cambio del ingreso se toque una vez.
 */
export async function ingresarComoAgente(user, username, password = "rentas123") {
  const { label } = WORKSPACES[AREA_POR_USUARIO[username]];

  await user.click(screen.getByRole("button", { name: /trabajo en el municipio/i }));
  await user.click(screen.getByRole("button", { name: label }));
  await user.type(screen.getByLabelText(/usuario/i), username);
  await user.type(screen.getByLabelText(/contraseña/i), password);
  await user.click(screen.getByRole("button", { name: new RegExp(`entrar a ${label}`, "i") }));
}

/** Ingresa por el acceso ciudadano, que es el que abre por defecto. */
export async function ingresarComoContribuyente(user, username = "jperez", password = "ciudadano123") {
  await user.type(screen.getByLabelText(/usuario/i), username);
  await user.type(screen.getByLabelText(/contraseña/i), password);
  await user.click(screen.getByRole("button", { name: /ingresar al portal/i }));
}
