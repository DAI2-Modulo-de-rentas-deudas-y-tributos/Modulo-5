import { expect } from "@playwright/test";

// Contraseña de los usuarios DEMO: la misma RENTAS_DEMO_USERS_PASSWORD del .env. Nunca se imprime.
export function demoPassword() {
  const password = process.env.E2E_DEMO_PASSWORD;
  if (!password) throw new Error("Falta E2E_DEMO_PASSWORD (el valor de RENTAS_DEMO_USERS_PASSWORD del .env).");
  return password;
}

export const HOME = {
  supervisor: "/rentas",
  rentas: "/rentas",
  cajero: "/caja",
  auditor: "/auditor",
  contribuyente: "/portal",
};

// Contribuyentes ficticios que carga qa/seed-e2e-local.sql; `contribuyente` es QA Ciudadano A.
export const CIUDADANO_A = { name: "QA Ciudadano A", dni: "99900001" };
export const CIUDADANO_B = { name: "QA Ciudadano B", dni: "99900002" };

export async function login(page, username, password = demoPassword()) {
  await page.goto("/login");
  // Con una sesión abierta, /login redirige a su área: se descarta para poder cambiar de usuario.
  await page.evaluate(() => sessionStorage.clear());
  await page.goto("/login");
  await page.getByRole("textbox", { name: "Usuario" }).fill(username);
  await page.getByRole("textbox", { name: "Contraseña" }).fill(password);
  await page.getByRole("button", { name: "Ingresar" }).click();
}

export async function loginAs(page, username) {
  await login(page, username);
  await expect(page).toHaveURL(new RegExp(`${HOME[username]}/?$`));
}

// Sesión opaca de la API, para las verificaciones que no pasan por la interfaz.
export async function apiSession(request, username) {
  const response = await request.post("/api/v1/dev-auth/login", {
    data: { username, password: demoPassword() },
  });
  expect(response.status()).toBe(200);
  const { token } = await response.json();
  return { "X-Demo-Session": token };
}

export async function taxpayerId(request, headers, dni) {
  const response = await request.get(`/api/v1/taxpayers?q=${dni}`, { headers });
  expect(response.status()).toBe(200);
  const [taxpayer] = (await response.json()).content;
  return taxpayer.id;
}
