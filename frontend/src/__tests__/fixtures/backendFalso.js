import { vi } from "vitest";
import { autenticar } from "./usuarios.js";

/**
 * Backend falso para las pruebas de flujo.
 *
 * La aplicación no tiene datos propios: todo llega por HTTP. Estas pruebas manejan
 * la interfaz completa, así que necesitan alguien del otro lado del `fetch`. Este
 * router responde con la forma de los DTO reales del backend, de modo que la
 * cadena rentasService → apiAdapters → fetch se ejercita entera.
 *
 * Las respuestas se declaran por prueba con `rutas`: lo que una prueba no declara
 * devuelve una colección vacía, que es lo que corresponde para una pantalla sin datos.
 */

/** Envoltorio Page de Spring, que es lo que devuelven las consultas paginadas. */
export const pagina = (content = []) => ({
  content,
  page: { number: 0, size: 100, totalElements: content.length, totalPages: 1 },
});

const json = (body, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });

const problema = (status, code, message) => json({ status, code, message, traceId: "test-trace" }, status);

/**
 * @param rutas mapa "MÉTODO /ruta" → cuerpo, o función (contexto) => cuerpo.
 *              La ruta se compara sin query string y admite `{id}` como comodín.
 */
export function instalarBackendFalso(rutas = {}) {
  const entradas = Object.entries(rutas).map(([clave, valor]) => {
    const [metodo, patron] = clave.split(" ");
    const expresion = new RegExp(`^${patron.replace(/\{id\}/g, "[^/]+")}$`);
    return { metodo, expresion, valor };
  });

  const llamadas = [];

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

    const encontrada = entradas.find((e) => e.metodo === metodo && e.expresion.test(ruta));
    if (encontrada) {
      const valor = typeof encontrada.valor === "function" ? encontrada.valor({ ruta, cuerpo, metodo }) : encontrada.valor;
      return json(valor ?? {});
    }

    // Sin declaración: colección vacía para las lecturas, eco para las escrituras.
    if (metodo === "GET") return json(pagina([]));
    return json({ id: 1, ...(cuerpo ?? {}) }, metodo === "POST" ? 201 : 200);
  });

  vi.stubGlobal("fetch", fetchFalso);
  fetchFalso.llamadas = llamadas;
  return fetchFalso;
}
