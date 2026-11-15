# Eliminar la capa de mocks del frontend — Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que el frontend obtenga toda su información del backend y que no quede forma de volver a los datos falsos.

**Architecture:** Se alinean los valores del frontend con los enums reales del backend, se reubican las constantes de catálogo a su fuente correcta, se eliminan las 99 ramas duales de `rentasService.js` dejando únicamente el camino HTTP, y se borra `mockDb.js` junto con el flag `USE_MOCKS`. El orden importa: las constantes consiguen un hogar nuevo antes de que se borre el archivo que hoy las contiene.

**Tech Stack:** React + Vite + Vitest. El backend (Spring Boot 3.5.5 / Java 17 / PostgreSQL 17) sólo se consulta.

**Spec:** `docs/superpowers/specs/2026-09-07-eliminar-capa-mocks-frontend-design.md`

## Global Constraints

- **El backend no se toca.** Ante cualquier desajuste entre frontend y backend, se corrige el frontend. No se agregan endpoints, enums, tablas ni migraciones Flyway.
- El repositorio y su documentación están en español: mantener ese idioma en commits, comentarios y documentación.
- `.editorconfig`: LF, UTF-8, indentación de 2 espacios, newline final.
- `AUTH_MODE` no se toca: es el punto de extensión para el JWT de Core/M9.
- No se modifica `src/config/modules.js`, `workspaces.js`, `cajaModules.js`, `portalModules.js` ni `auditoriaModules.js` (navegación).
- Configuración siempre por variables de entorno; nada de URLs ni credenciales en el código.

## Valores del backend (fuente de verdad)

Copiados de `backend/src/main/java/ar/gob/municipalidad/rentas/DomainEntities.java`. El frontend debe usar exactamente estos:

```
PaymentMethod          { CASH, CARD, TRANSFER, DIGITAL_WALLET }
PaymentOrigin          { CASHIER, ELECTRONIC, EXTERNAL }
DebtOriginType         { LIQUIDATION, EXTERNAL_OBLIGATION }
ExternalObligationType { PERMIT_FEE, COMMERCIAL_FINE, TRAFFIC_INFRACTION }
```

## Entorno de trabajo

Con Docker Desktop abierto:

```bash
cd ~/Modulo-5-3
docker compose up -d postgres          # o usar el PostgreSQL de Homebrew ya existente
cd backend && ./mvnw spring-boot:run   # DB_URL/DB_USER/DB_PASSWORD según backend/.env.example
```

Nota: en esta máquina hay un PostgreSQL de Homebrew (`postgresql@17`) escuchando en `127.0.0.1:5432`. `localhost` resuelve a ese, no al contenedor. Usar uno u otro, no ambos. El comando del wrapper es `./mvnw`, no `mvnw.cmd` (ése es el batch de Windows).

## Estructura de archivos

| Archivo | Responsabilidad | Acción |
|---|---|---|
| `frontend/src/config/catalogosDominio.js` | Opciones de los enums del backend con etiqueta en español | Crear |
| `frontend/src/config/etiquetasModulos.js` | Nombres de los módulos externos (M1…M9) | Crear |
| `frontend/src/services/rentasService.js` | Acceso HTTP al backend, sin ramas mock | Modificar |
| `frontend/src/services/apiClient.js` | Cliente HTTP, sin `USE_MOCKS` ni `delay()` | Modificar |
| `frontend/src/services/apiAdapters.js` | Adaptación de rutas y formas, sin traducción de enums | Modificar |
| `frontend/src/services/mockDb.js` | — | Borrar |
| `frontend/.env` | Apunta el front al backend | Crear (ignorado por git) |

---

### Task 1: Catálogos de dominio alineados al backend

**Files:**
- Create: `frontend/src/config/catalogosDominio.js`
- Create: `frontend/src/config/etiquetasModulos.js`
- Test: `frontend/src/config/catalogosDominio.test.js`

**Interfaces:**
- Produces: `PAYMENT_METHODS`, `PAYMENT_ORIGINS`, `DEBT_ORIGIN_TYPES`, `EXTERNAL_OBLIGATION_TYPES`, cada uno `[{value,label}]`, y `labelOf(catalogo, valor)`. `MODULE_LABELS` desde `etiquetasModulos.js`.

- [ ] **Step 1: Escribir el test que falla**

Crear `frontend/src/config/catalogosDominio.test.js`:

```js
import { describe, it, expect } from "vitest";
import {
  PAYMENT_METHODS,
  PAYMENT_ORIGINS,
  DEBT_ORIGIN_TYPES,
  EXTERNAL_OBLIGATION_TYPES,
  labelOf,
} from "./catalogosDominio.js";

/** Si el backend cambia un enum, estas pruebas fallan y avisan. */
describe("catálogos de dominio", () => {
  it("usa exactamente los medios de pago del backend", () => {
    expect(PAYMENT_METHODS.map((o) => o.value)).toEqual(["CASH", "CARD", "TRANSFER", "DIGITAL_WALLET"]);
  });

  it("usa exactamente los orígenes de pago del backend", () => {
    expect(PAYMENT_ORIGINS.map((o) => o.value)).toEqual(["CASHIER", "ELECTRONIC", "EXTERNAL"]);
  });

  it("separa el origen de la deuda del tipo de obligación externa", () => {
    expect(DEBT_ORIGIN_TYPES.map((o) => o.value)).toEqual(["LIQUIDATION", "EXTERNAL_OBLIGATION"]);
    expect(EXTERNAL_OBLIGATION_TYPES.map((o) => o.value)).toEqual(["PERMIT_FEE", "COMMERCIAL_FINE", "TRAFFIC_INFRACTION"]);
  });

  it("no conserva ningún valor inventado por el frontend", () => {
    const todos = [...PAYMENT_METHODS, ...PAYMENT_ORIGINS, ...DEBT_ORIGIN_TYPES, ...EXTERNAL_OBLIGATION_TYPES];
    for (const opcion of todos) {
      expect(opcion.value).toMatch(/^[A-Z_]+$/);
    }
    expect(todos.map((o) => o.value)).not.toContain("SETTLEMENT");
    expect(todos.map((o) => o.value)).not.toContain("EFECTIVO");
    expect(todos.map((o) => o.value)).not.toContain("TARJETA");
  });

  it("devuelve el valor crudo si no conoce la etiqueta", () => {
    expect(labelOf(PAYMENT_METHODS, "CASH")).toBe("Efectivo");
    expect(labelOf(PAYMENT_METHODS, "DESCONOCIDO")).toBe("DESCONOCIDO");
  });
});
```

- [ ] **Step 2: Correr el test y verificar que falla**

```bash
cd frontend && npm test -- src/config/catalogosDominio.test.js
```

Esperado: FAIL, no se puede resolver `./catalogosDominio.js`.

- [ ] **Step 3: Implementar los catálogos**

Crear `frontend/src/config/catalogosDominio.js`:

```js
/**
 * Catálogos de los enums del backend.
 *
 * Los `value` son los del dominio y no se traducen: mandar un valor que el backend
 * no conoce termina en un filtro rechazado o un guardado fallido. El `label` es
 * sólo rotulación de pantalla.
 *
 * Fuente: DomainEntities.java. Si allá se agrega un valor, se agrega acá y la
 * prueba de catalogosDominio.test.js avisa del desajuste.
 */

export const PAYMENT_METHODS = [
  { value: "CASH", label: "Efectivo" },
  { value: "CARD", label: "Tarjeta" },
  { value: "TRANSFER", label: "Transferencia" },
  { value: "DIGITAL_WALLET", label: "Billetera virtual / QR" },
];

export const PAYMENT_ORIGINS = [
  { value: "CASHIER", label: "Ventanilla" },
  { value: "ELECTRONIC", label: "Electrónico" },
  { value: "EXTERNAL", label: "Externo" },
];

export const DEBT_ORIGIN_TYPES = [
  { value: "LIQUIDATION", label: "Liquidación propia" },
  { value: "EXTERNAL_OBLIGATION", label: "Obligación externa" },
];

/**
 * Tipos de obligación externa. No son valores de `Debt.originType`: para acotar
 * deudas por M4 o M7 se filtra por concepto tributario, que es donde el backend
 * los representa.
 */
export const EXTERNAL_OBLIGATION_TYPES = [
  { value: "PERMIT_FEE", label: "Tasa de habilitación (M4)" },
  { value: "COMMERCIAL_FINE", label: "Multa comercial (M4)" },
  { value: "TRAFFIC_INFRACTION", label: "Infracción de tránsito (M7)" },
];

/** Etiqueta de un valor, o el valor crudo si el catálogo no lo contempla. */
export const labelOf = (catalogo, valor) =>
  catalogo.find((opcion) => opcion.value === valor)?.label ?? valor;
```

Crear `frontend/src/config/etiquetasModulos.js`:

```js
/**
 * Nombres de los módulos del sistema municipal.
 *
 * No salen de la base: son módulos ajenos a Rentas y ninguna tabla local los
 * registra. Es rotulación de presentación, no dato de negocio.
 */
export const MODULE_LABELS = {
  M1: "M1 — Ciudadanos",
  M2: "M2 — Atención Ciudadana",
  M4: "M4 — Habilitaciones",
  M5: "M5 — Rentas",
  M7: "M7 — Tránsito",
  M8: "M8 — Desarrollo Social",
  M9: "M9 — Core",
};
```

- [ ] **Step 4: Correr el test y verificar que pasa**

```bash
cd frontend && npm test -- src/config/catalogosDominio.test.js
```

Esperado: PASS, 5 pruebas.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/config/catalogosDominio.js frontend/src/config/catalogosDominio.test.js frontend/src/config/etiquetasModulos.js
git commit -m "feat(frontend): agrega los catálogos de dominio alineados al backend"
```

---

### Task 2: Borrar la traducción de enums del adaptador

**Files:**
- Modify: `frontend/src/services/apiAdapters.js` — líneas 5, 6, 7, 40, 72, 192, 195, 276
- Test: `frontend/src/services/apiAdapters.test.js`

**Interfaces:**
- Consumes: nada de la Task 1; este cambio sólo elimina traducción.
- Produces: `enumMappings` deja de exportar `METHOD_TO_API`, `METHOD_FROM_API` y `ORIGIN_FROM_API`.

**Contexto:** `METHOD_TO_API` colapsa débito y crédito en `CARD`; `METHOD_FROM_API` devuelve `"TARJETA"`, un valor que ninguna opción contiene. `ORIGIN_FROM_API` convierte `LIQUIDATION → SETTLEMENT` y la línea 72 hace el camino inverso sobre la query string. Con mocks el round-trip nunca ocurre y nada de esto se nota.

- [ ] **Step 1: Escribir el test que falla**

Agregar a `frontend/src/services/apiAdapters.test.js`:

```js
it("no traduce los enums del backend", async () => {
  const { enumMappings } = await import("./apiAdapters.js");
  expect(enumMappings.METHOD_TO_API).toBeUndefined();
  expect(enumMappings.METHOD_FROM_API).toBeUndefined();
  expect(enumMappings.ORIGIN_FROM_API).toBeUndefined();
});
```

- [ ] **Step 2: Correr el test y verificar que falla**

```bash
cd frontend && npm test -- src/services/apiAdapters.test.js
```

Esperado: FAIL, los tres mapas siguen exportados en `enumMappings` (línea 276).

- [ ] **Step 3: Eliminar los mapas y sus usos**

En `frontend/src/services/apiAdapters.js`:

1. Borrar las líneas 5, 6 y 7 (`METHOD_TO_API`, `METHOD_FROM_API`, `ORIGIN_FROM_API`).
2. Borrar la línea 40: `const method = (value) => METHOD_TO_API[value] ?? value ?? "CASH";` y reemplazar cada invocación `method(x)` por `x`.
3. Borrar la línea 72: `path = path.replace("originType=SETTLEMENT", "originType=LIQUIDATION");`
4. En la línea 192, reemplazar `originType: ORIGIN_FROM_API[row.originType] ?? row.originType` por `originType: row.originType`.
5. En la línea 195, reemplazar `method: METHOD_FROM_API[row.paymentMethod] ?? row.paymentMethod` por `method: row.paymentMethod`.
6. Quitar los tres nombres de `enumMappings` en la línea 276.

Verificar que no quedó ninguna referencia:

```bash
cd frontend && grep -n "METHOD_TO_API\|METHOD_FROM_API\|ORIGIN_FROM_API\|method(" src/services/apiAdapters.js
```

Esperado: sin resultados.

- [ ] **Step 4: Correr las pruebas**

```bash
cd frontend && npm test -- src/services/apiAdapters.test.js
```

Esperado: PASS. Si alguna prueba afirma sobre `"EFECTIVO"`, `"TARJETA"` o `"SETTLEMENT"`, actualizarla al valor del backend.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/services/apiAdapters.js frontend/src/services/apiAdapters.test.js
git commit -m "fix(frontend): deja de traducir los enums del backend"
```

---

### Task 3: Reubicar las constantes de catálogo en las páginas

**Files:**
- Modify: los 14 archivos de `src/pages` y `src/components` que importan desde `mockDb.js`

**Interfaces:**
- Consumes: `catalogosDominio.js` y `etiquetasModulos.js` de la Task 1; `useTaxConcepts()` ya existente.

- [ ] **Step 1: Listar los archivos afectados**

```bash
cd frontend && grep -rn "mockDb" src/pages src/components
```

- [ ] **Step 2: Reemplazar import por import, según esta tabla**

| Importaba | Pasa a |
|---|---|
| `MODULE_LABELS` | `import { MODULE_LABELS } from "../../config/etiquetasModulos.js"` |
| `PAYMENT_METHODS` | `import { PAYMENT_METHODS } from "../../config/catalogosDominio.js"` |
| `ORIGIN_TYPES` | `import { DEBT_ORIGIN_TYPES } from "../../config/catalogosDominio.js"` — ver Step 3 |
| `conceptDefinitions` | `const { concepts } = useTaxConcepts()` |
| `REFINANCING_RULES` | `await request("/api/v1/payment-plan-configurations")` — tomar la configuración activa |
| `BUSINESS_DATE` | `new Date().toISOString().slice(0, 10)` |

`MODULE_LABELS` y `PAYMENT_METHODS` son reemplazos puros de ruta. `conceptDefinitions` y `REFINANCING_RULES` pasan de constante a estado asincrónico: el componente necesita contemplar `loading` antes de renderizar.

- [ ] **Step 3: Corregir el filtro de origen en la pantalla de deudas**

En `src/pages/rentas/DeudasPage.jsx`, el combo de origen usaba los cuatro valores aplanados. Pasa a usar `DEBT_ORIGIN_TYPES` (`LIQUIDATION`, `EXTERNAL_OBLIGATION`), que son los únicos que `Debt.originType` acepta.

Quien necesite acotar por M4 o M7 lo hace por el filtro de concepto tributario, que ya existe en la pantalla y sí está soportado por el backend.

- [ ] **Step 4: Verificar que ninguna página importa de mockDb**

```bash
cd frontend && grep -rn "mockDb" src/pages src/components src/hooks
```

Esperado: sin resultados.

- [ ] **Step 5: Correr las pruebas y el build**

```bash
cd frontend && npm test && npm run build
```

- [ ] **Step 6: Commit**

```bash
git add frontend/src
git commit -m "refactor(frontend): las páginas toman los catálogos de su fuente real"
```

---

### Tasks 4 a 8: Eliminar las ramas mock de `rentasService.js`

Cinco tareas con la misma mecánica sobre tramos distintos del archivo. **La transformación en cada método es:**

```js
// ANTES
async search({ query = "" } = {}) {
  if (!USE_MOCKS) {
    const rows = await request(`/api/v1/taxpayers?q=${query}`);
    return rows.content;
  }
  await delay();
  return db.taxpayers.filter((t) => t.displayName.includes(query));
}

// DESPUÉS
async search({ query = "" } = {}) {
  const rows = await request(`/api/v1/taxpayers?q=${query}`);
  return rows.content;
}
```

Se conserva el cuerpo del `if (!USE_MOCKS)`, se le quita un nivel de indentación, y se borra todo lo que venía después dentro del método. La forma abreviada `if (!USE_MOCKS) return request(...)` se convierte en `return request(...)`.

**Cuidado:** las guardas `if (AUTH_MODE === "core")` se conservan tal cual. No confundirlas con las de `USE_MOCKS`.

Tramos (los números de línea se corren a medida que se borra; ubicar por el nombre del `export const`):

| Task | Servicios | Commit |
|---|---|---|
| 4 | `authService`, `taxpayerService`, `taxConfigService` | `refactor(frontend): elimina la rama mock de auth, padrón y catálogo` |
| 5 | `settlementService`, `debtService`, `administrationService`, `reconciliationService`, `debtAdjustmentService`, `billService` | `refactor(frontend): elimina la rama mock de liquidaciones, deudas y boletas` |
| 6 | `paymentService`, `creditBalanceService`, `paymentPlanService`, `refinancingService` | `refactor(frontend): elimina la rama mock de pagos y planes` |
| 7 | `exemptionService`, `ticketService`, `eventService`, `cashierService` | `refactor(frontend): elimina la rama mock de exenciones, tickets y caja` |
| 8 | `auditService`, `portalService`, `dashboardService` | `refactor(frontend): elimina la rama mock de auditoría y portal` |

**Pasos de cada una de las cinco tareas:**

- [ ] **Step 1: Contar las ramas antes de empezar**

```bash
cd frontend && grep -c "USE_MOCKS" src/services/rentasService.js
```

Anotar el número para comprobar que baja al terminar.

- [ ] **Step 2: Aplicar la transformación a cada método del tramo**

Método por método, según el patrón de arriba.

- [ ] **Step 3: Verificar que el tramo ya no tiene ramas mock**

```bash
cd frontend && grep -n "USE_MOCKS" src/services/rentasService.js
```

Esperado: ninguna ocurrencia dentro de los servicios del tramo.

- [ ] **Step 4: Correr las pruebas**

```bash
cd frontend && npm test
```

Esperado: verde. Si una prueba dependía de la rama mock de este tramo, adaptarla a `fetch` simulado como hacen `taxConfigApi.test.js` y `apiAdapters.test.js`.

- [ ] **Step 5: Commit**

Con el mensaje de la tabla.

---

### Task 9: Borrar `mockDb.js`, el flag y la latencia simulada

**Files:**
- Delete: `frontend/src/services/mockDb.js`
- Modify: `frontend/src/services/apiClient.js`, `frontend/src/services/rentasService.js:9`
- Modify: `frontend/src/components/documentos/BillPdfDownload.jsx:36-37`

- [ ] **Step 1: Verificar que no queda ninguna referencia de producción**

```bash
cd frontend && grep -rn "mockDb\|USE_MOCKS" src | grep -v "\.test\."
```

Esperado: sólo las líneas de `apiClient.js`, `rentasService.js:9` y `BillPdfDownload.jsx`, que se limpian en este task.

- [ ] **Step 2: Borrar el archivo y las constantes**

```bash
cd frontend && git rm src/services/mockDb.js
```

En `src/services/apiClient.js` eliminar la línea:

```js
export const USE_MOCKS = import.meta.env.VITE_USE_MOCKS !== "false";
```

y la función `delay()` con su comentario (`/** Simula la latencia de red… */`). Reemplazar el comentario de cabecera, que hoy dice «Mientras el backend no exista, `VITE_USE_MOCKS` mantiene la app navegable contra el dataset local de `mockDb.js`»:

```js
/**
 * Cliente HTTP del frontend de Rentas.
 *
 * La URL del backend llega siempre por variable de entorno (`VITE_API_BASE_URL`),
 * nunca hardcodeada. Toda la información proviene del backend: no hay dataset local.
 */
```

En `src/services/rentasService.js:9` dejar:

```js
import { AUTH_MODE, request, ApiError } from "./apiClient.js";
```

y borrar la línea `import * as db from "./mockDb.js";`.

En `src/components/documentos/BillPdfDownload.jsx`, el botón se deshabilita con `disabled={loading || USE_MOCKS}`: pasa a `disabled={loading}`. Eliminar el `title` condicional y dejar `title="Descargar boleta PDF"`, y quitar `USE_MOCKS` del import de la línea 3.

- [ ] **Step 3: Limpiar los stubs de entorno en los tests**

Quitar las líneas `vi.stubEnv("VITE_USE_MOCKS", "false")` de:

- `src/services/apiAdapters.test.js`
- `src/services/taxConfigApi.test.js`
- `src/__tests__/conceptosDesdeCatalogo.test.jsx`
- `src/components/documentos/BillPdfDownload.test.jsx`

En `src/services/refinancing.test.js`, reemplazar las fixtures que venían de `mockDb` por objetos literales definidos en el propio archivo.

Las pruebas que afirman «no cae a mockDb ante un error de red» se conservan: siguen siendo una guarda válida contra la reintroducción de un fallback.

- [ ] **Step 4: Correr todo**

```bash
cd frontend && npm test && npm run build
```

Esperado: los 24 archivos de pruebas en verde y build exitoso.

- [ ] **Step 5: Verificación final de que no queda hardcode**

```bash
cd frontend && grep -rn "mockDb\|USE_MOCKS" src
```

Esperado: sin resultados.

- [ ] **Step 6: Commit**

```bash
git add frontend
git commit -m "refactor(frontend): borra el dataset local y el flag de mocks"
```

---

### Task 10: Configuración y documentación

**Files:**
- Create: `frontend/.env`
- Modify: `frontend/.env.example`, `README.md`

- [ ] **Step 1: Crear el `.env` local**

```bash
cd frontend && cat > .env <<'EOF'
VITE_API_BASE_URL=http://localhost:8080
VITE_AUTH_MODE=mock
VITE_DEV_IDENTITY_HEADERS=false
EOF
git check-ignore -v .env
```

Esperado: `git check-ignore` confirma que está ignorado.

- [ ] **Step 2: Actualizar el ejemplo**

En `frontend/.env.example` eliminar el bloque de `VITE_USE_MOCKS`: el comentario «Fuerza el dataset local aunque `VITE_API_BASE_URL` esté configurada.» y la variable.

- [ ] **Step 3: Anotar la consecuencia en el README**

Agregar a la sección de desarrollo del frontend:

```markdown
El frontend consume exclusivamente el backend: no hay dataset local. Antes de
`npm run dev` hay que tener PostgreSQL y el backend levantados, o las pantallas
mostrarán errores de red.
```

- [ ] **Step 4: Verificación manual de extremo a extremo**

Con PostgreSQL y el backend corriendo:

```bash
cd frontend && npm run dev
```

En el navegador:

1. Iniciar sesión con un usuario demo.
2. Registrar un pago con tarjeta, recargar y confirmar que el medio de pago se ve bien — el caso que hoy falla por el mapeo lossy.
3. En la pantalla de deudas, filtrar por origen y confirmar que devuelve resultados en vez de `INVALID_FILTER_VALUE` — el otro caso que los mocks ocultaban.

- [ ] **Step 5: Commit**

```bash
git add frontend/.env.example README.md
git commit -m "docs: el frontend consume sólo el backend"
```

---

## Verificación final

```bash
cd frontend && npm test && npm run build
grep -rn "mockDb\|USE_MOCKS" frontend/src   # sin resultados
git -C .. status --short                    # sin archivos del backend modificados
```

El backend no debe aparecer en el diff. Si aparece, algo se salió del alcance.
