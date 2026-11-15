/**
 * Cuentas demo, con la forma que devuelve `/api/v1/dev-auth/login`.
 *
 * Son fixtures de prueba: el código de producción no las conoce. Los roles son los
 * del backend (`DemoRole`), no los rótulos de la interfaz.
 */
export const USUARIOS = [
  {
    credenciales: { username: "mrivas", password: "rentas123" },
    usuario: { id: 1, username: "mrivas", displayName: "Mariana Rivas", role: "RENTAS", authorities: ["RENTAS"], taxpayerId: null, active: true },
  },
  {
    credenciales: { username: "jlopez", password: "rentas123" },
    usuario: { id: 2, username: "jlopez", displayName: "Julián López", role: "SUPERVISOR", authorities: ["RENTAS", "SUPERVISOR"], taxpayerId: null, active: true },
  },
  {
    credenciales: { username: "pcabrera", password: "caja123" },
    usuario: { id: 3, username: "pcabrera", displayName: "Paula Cabrera", role: "CASHIER", authorities: ["CASHIER"], taxpayerId: null, active: true },
  },
  {
    credenciales: { username: "acastro", password: "audit123" },
    usuario: { id: 4, username: "acastro", displayName: "Ana Castro", role: "AUDITOR", authorities: ["AUDITOR"], taxpayerId: null, active: true },
  },
  {
    credenciales: { username: "jperez", password: "ciudadano123" },
    usuario: { id: 5, username: "jperez", displayName: "Juan Pérez", role: "TAXPAYER", authorities: ["TAXPAYER"], taxpayerId: 123, active: true },
  },
];

export function autenticar(username, password) {
  const encontrado = USUARIOS.find(
    (u) => u.credenciales.username === String(username ?? "").trim().toLowerCase() && u.credenciales.password === password,
  );
  return encontrado?.usuario ?? null;
}
