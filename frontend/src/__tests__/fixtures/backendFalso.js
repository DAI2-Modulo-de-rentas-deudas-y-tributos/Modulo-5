import { vi } from "vitest";
import { autenticar } from "./usuarios.js";
import * as datos from "./datos.js";

/**
 * Backend falso para las pruebas de flujo.
 *
 * La aplicación no tiene datos propios: todo llega por HTTP. Estas pruebas manejan
 * la interfaz completa, así que necesitan alguien del otro lado del `fetch`. Este
 * router responde con la forma de los DTO reales, de modo que la cadena
 * rentasService → apiAdapters → fetch se ejercita entera.
 *
 * Sirve el dataset de `datos.js` por defecto; cada prueba puede sobrescribir la
 * ruta que le interese con `rutas`. Lo que nadie declara devuelve una colección
 * vacía, que es lo que corresponde para una pantalla sin datos.
 */

/** Envoltorio Page de Spring, que es lo que devuelven las consultas paginadas. */
export const pagina = (content = []) => ({
  content,
  page: { number: 0, size: 100, totalElements: content.length, totalPages: 1 },
});

const json = (body, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });

const problema = (status, code, message) => json({ status, code, message, traceId: "test-trace" }, status);

const porId = (coleccion, id) => coleccion.find((x) => String(x.id) === String(id)) ?? null;

/** Colecciones que el router sirve sin que la prueba tenga que declararlas. */
const COLECCIONES = [
  [/^\/api\/v1\/taxpayers$/, () => datos.CONTRIBUYENTES],
  [/^\/api\/v1\/tax-concepts$/, () => datos.CONCEPTOS],
  [/^\/api\/v1\/tax-configurations$/, () => datos.CONFIGURACIONES],
  [/^\/api\/v1\/liquidations$/, () => datos.LIQUIDACIONES],
  [/^\/api\/v1\/liquidation-runs$/, () => []],
  [/^\/api\/v1\/debts$/, () => datos.DEUDAS],
  [/^\/api\/v1\/bills$/, () => datos.BOLETAS],
  [/^\/api\/v1\/payments$/, () => datos.PAGOS],
  [/^\/api\/v1\/credit-balances$/, () => datos.SALDOS],
  [/^\/api\/v1\/payment-plan-requests$/, () => datos.SOLICITUDES_PLAN],
  [/^\/api\/v1\/payment-plans$/, () => datos.PLANES],
  [/^\/api\/v1\/exemption-requests$/, () => datos.SOLICITUDES_EXENCION],
  [/^\/api\/v1\/exemptions$/, () => []],
  [/^\/api\/v1\/tickets$/, () => datos.TICKETS],
  [/^\/api\/v1\/integrations\/events$/, () => datos.EVENTOS],
  [/^\/api\/v1\/audit$/, () => datos.AUDITORIA],
  [/^\/api\/v1\/adjustments$/, () => []],
  [/^\/api\/v1\/payment-reversals$/, () => []],
  [/^\/api\/v1\/payment-allocations$/, () => []],
];

/** Recursos individuales y anidados. */
const RECURSOS = [
  [/^\/api\/v1\/taxpayers\/(\d+)$/, ([id]) => porId(datos.CONTRIBUYENTES, id)],
  [/^\/api\/v1\/taxpayers\/(\d+)\/summary$/, ([id]) => ({
    taxpayer: porId(datos.CONTRIBUYENTES, id),
    totalOutstanding: datos.DEUDAS.filter((d) => String(d.taxpayerId) === id).reduce((a, d) => a + d.outstandingBalance, 0),
    overdueCount: datos.DEUDAS.filter((d) => String(d.taxpayerId) === id && d.overdue).length,
  })],
  [/^\/api\/v1\/taxpayers\/(\d+)\/debts$/, ([id]) => pagina(datos.DEUDAS.filter((d) => String(d.taxpayerId) === id))],
  [/^\/api\/v1\/taxpayers\/(\d+)\/bills$/, ([id]) => pagina(datos.BOLETAS.filter((b) => String(b.taxpayerId) === id))],
  [/^\/api\/v1\/taxpayers\/(\d+)\/payments$/, ([id]) => pagina(datos.PAGOS.filter((p) => String(p.taxpayerId) === id))],
  [/^\/api\/v1\/taxpayers\/(\d+)\/credit-balances$/, ([id]) => pagina(datos.SALDOS.filter((c) => String(c.taxpayerId) === id))],
  [/^\/api\/v1\/taxpayers\/(\d+)\/payment-plans$/, ([id]) => pagina(datos.PLANES.filter((p) => String(p.taxpayerId) === id))],
  [/^\/api\/v1\/taxpayers\/(\d+)\/payment-plan-requests$/, ([id]) => pagina(datos.SOLICITUDES_PLAN.filter((p) => String(p.taxpayerId) === id))],
  [/^\/api\/v1\/taxpayers\/(\d+)\/exemptions$/, () => pagina([])],
  [/^\/api\/v1\/taxpayers\/(\d+)\/exemption-requests$/, ([id]) => pagina(datos.SOLICITUDES_EXENCION.filter((e) => String(e.taxpayerId) === id))],
  [/^\/api\/v1\/taxpayers\/(\d+)\/benefits$/, () => pagina([])],
  [/^\/api\/v1\/taxpayers\/(\d+)\/debts\/summary$/, ([id]) => ({
    totalOutstanding: datos.DEUDAS.filter((d) => String(d.taxpayerId) === id).reduce((a, d) => a + d.outstandingBalance, 0),
    overdueCount: datos.DEUDAS.filter((d) => String(d.taxpayerId) === id && d.overdue).length,
  })],
  [/^\/api\/v1\/debts\/(\d+)$/, ([id]) => porId(datos.DEUDAS, id)],
  [/^\/api\/v1\/bills\/(\d+)$/, ([id]) => porId(datos.BOLETAS, id)],
  [/^\/api\/v1\/payments\/(\d+)$/, ([id]) => porId(datos.PAGOS, id)],
  [/^\/api\/v1\/liquidations\/(\d+)$/, ([id]) => porId(datos.LIQUIDACIONES, id)],
  [/^\/api\/v1\/tax-concepts\/(\d+)$/, ([id]) => porId(datos.CONCEPTOS, id)],
  [/^\/api\/v1\/tax-configurations\/(\d+)$/, ([id]) => porId(datos.CONFIGURACIONES, id)],
  [/^\/api\/v1\/credit-balances\/(\d+)$/, ([id]) => porId(datos.SALDOS, id)],
  [/^\/api\/v1\/payment-plan-requests\/(\d+)$/, ([id]) => porId(datos.SOLICITUDES_PLAN, id)],
  [/^\/api\/v1\/payment-plans\/(\d+)$/, ([id]) => porId(datos.PLANES, id)],
  [/^\/api\/v1\/payment-plans\/(\d+)\/installments$/, () => pagina([])],
  [/^\/api\/v1\/exemption-requests\/(\d+)$/, ([id]) => porId(datos.SOLICITUDES_EXENCION, id)],
  [/^\/api\/v1\/tickets\/(\d+)$/, ([id]) => porId(datos.TICKETS, id)],
  [/^\/api\/v1\/integrations\/events\/([^/]+)$/, ([id]) => datos.EVENTOS.find((e) => e.eventId === id || String(e.id) === id) ?? null],
  [/^\/api\/v1\/audit\/(\d+)$/, ([id]) => porId(datos.AUDITORIA, id)],
  [/^\/api\/v1\/indicators\/summary$/, () => datos.INDICADORES],
  [/^\/api\/v1\/indicators\/(collection|debt|delinquency)$/, ([k]) => datos.INDICADORES[k]],
  [/^\/api\/v1\/health$/, () => ({ status: "UP" })],
];

/**
 * @param rutas mapa "MÉTODO /ruta" → cuerpo, o función (contexto) => cuerpo.
 *              La ruta se compara sin query string y admite `{id}` como comodín.
 *              Lo declarado acá tiene prioridad sobre el dataset por defecto.
 */
export function instalarBackendFalso(rutas = {}) {
  const entradas = Object.entries(rutas).map(([clave, valor]) => {
    const [metodo, patron] = clave.split(" ");
    return { metodo, expresion: new RegExp(`^${patron.replace(/\{id\}/g, "[^/]+")}$`), valor };
  });

  const llamadas = [];
  let siguienteId = 90000;

  const fetchFalso = vi.fn(async (url, options = {}) => {
    const metodo = (options.method ?? "GET").toUpperCase();
    const ruta = String(url).split("?")[0].replace(/^https?:\/\/[^/]+/, "");
    const cuerpo = options.body ? JSON.parse(options.body) : null;
    llamadas.push({ metodo, ruta, cuerpo });

    if (metodo === "POST" && ruta === "/api/v1/dev-auth/login") {
      const usuario = autenticar(cuerpo?.username, cuerpo?.password);
      if (!usuario) return problema(401, "INVALID_CREDENTIALS", "Usuario o contraseña incorrectos");
      return json({ token: "test-session", user: usuario });
    }
    if (metodo === "GET" && ruta === "/api/v1/dev-auth/me") {
      return json(autenticar("mrivas", "rentas123"));
    }

    const declarada = entradas.find((e) => e.metodo === metodo && e.expresion.test(ruta));
    if (declarada) {
      const valor = typeof declarada.valor === "function" ? declarada.valor({ ruta, cuerpo, metodo }) : declarada.valor;
      return json(valor ?? {});
    }

    if (metodo === "GET") {
      for (const [expresion, resolver] of RECURSOS) {
        const m = ruta.match(expresion);
        if (m) {
          const valor = resolver(m.slice(1));
          return valor === null ? problema(404, "NOT_FOUND", "No encontrado") : json(valor);
        }
      }
      for (const [expresion, resolver] of COLECCIONES) {
        if (expresion.test(ruta)) return json(pagina(resolver()));
      }
      return json(pagina([]));
    }

    // Escritura sin declarar: eco con un id nuevo, que es lo que devuelve el backend.
    return json({ id: ++siguienteId, ...(cuerpo ?? {}) }, metodo === "POST" ? 201 : 200);
  });

  vi.stubGlobal("fetch", fetchFalso);
  fetchFalso.llamadas = llamadas;
  return fetchFalso;
}
