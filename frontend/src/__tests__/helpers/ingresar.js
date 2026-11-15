import { screen } from "@testing-library/react";

/**
 * Ingresa con una cuenta de agente. El backend devuelve el rol y la aplicación deriva
 * al área correspondiente sin pedirle al usuario que la elija.
 */
export async function ingresarComoAgente(user, username, password = "rentas123") {
  await user.type(screen.getByLabelText(/usuario/i), username);
  await user.type(screen.getByLabelText(/contraseña/i), password);
  await user.click(screen.getByRole("button", { name: /^ingresar$/i }));
}

/** Ingresa con una cuenta de contribuyente desde el mismo acceso unificado. */
export async function ingresarComoContribuyente(user, username = "jperez", password = "ciudadano123") {
  await user.type(screen.getByLabelText(/usuario/i), username);
  await user.type(screen.getByLabelText(/contraseña/i), password);
  await user.click(screen.getByRole("button", { name: /^ingresar$/i }));
}
