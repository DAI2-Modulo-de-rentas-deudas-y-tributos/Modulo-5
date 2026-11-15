# Eliminar la capa de mocks del frontend

Fecha: 2026-09-07
Estado: propuesto

## Problema

El frontend no consume el backend. No existe `frontend/.env`, y en
`src/services/apiClient.js` el flag se define así:

```js
export const USE_MOCKS = import.meta.env.VITE_USE_MOCKS !== "false";
```

Con la variable indefinida la comparación da `true`, de modo que `npm run dev`
levanta la aplicación contra `src/services/mockDb.js` y nunca emite una request.
Lo mismo ocurre con `AUTH_MODE` (`?? "mock"`) y `API_BASE_URL` (`?? ""`).

El camino real existe y fue validado —`rentasService.js` mantiene las dos ramas y
el reporte de integración documenta la cadena UI → API → PostgreSQL con
`VITE_USE_MOCKS=false`— pero está apagado por defecto y convive con 1536 líneas de
datos inventados.

## Objetivo

Que el frontend obtenga toda su información del backend, y que no exista forma de
volver a los datos falsos.

## Alcance

### Se elimina

| Elemento | Ubicación | Tamaño |
|---|---|---|
| Dataset falso completo | `src/services/mockDb.js` | 1536 líneas |
| Ramas duales mock/real | `src/services/rentasService.js` | 99 referencias a `USE_MOCKS` |
| Flag `USE_MOCKS` | `src/services/apiClient.js` | 1 constante |
| Simulación de latencia `delay()` | `src/services/apiClient.js` | — |

### No se toca

`AUTH_MODE` permanece. No pertenece a la capa de mocks: es el punto de extensión
para el JWT de Core/M9, que todavía no tiene contrato definido
(`if (AUTH_MODE === "core")` en `AuthContext.jsx` y `rentasService.js`).
Eliminarlo borraría un seam previsto. Su valor `"mock"` designa la autenticación
dev-auth, que ya persiste contra la tabla `demo_user`.

`FilteredQueryService.java` contiene 24 colecciones literales, pero son la
allowlist de campos filtrables por entidad: un control de seguridad que impide
filtrar por campos arbitrarios. Llevarlo a la base de datos degradaría esa
garantía.

`src/config/*.js` define rutas, íconos y etiquetas de navegación. Es
configuración de presentación, no dato de negocio.

## Destino de las constantes de catálogo

Catorce archivos entre páginas y componentes importan constantes desde `mockDb.js`.

| Constante | Destino | Estado |
|---|---|---|
| `conceptDefinitions`, `CONCEPTS` | `GET /api/v1/tax-configurations` | Endpoint existente; `useTaxConcepts.js` ya lo usa |
| `REFINANCING_RULES` | `GET /api/v1/payment-plan-configurations` | Endpoint existente |
| `PAYMENT_METHODS`, `ORIGIN_TYPES` | `src/config/catalogosDominio.js` | Constantes alineadas a los enums del backend |
| `MODULE_LABELS` | `src/config/modulos.js` | Etiquetas de otros módulos (M1 → "Ciudadanos"); ninguna tabla las respalda |
| `BUSINESS_DATE` | Se elimina | Era un "hoy" fijo (`2026-08-25`); pasa a ser la fecha real |
| `USERS` | Se elimina | La autenticación ya va por `/api/v1/dev-auth` contra la tabla `demo_user` |

## El backend no se toca

Restricción del proyecto: ante cualquier desajuste entre frontend y backend, se
corrige el frontend. No se agregan endpoints, enums, tablas ni migraciones.

En consecuencia, los enums que hoy no tienen endpoint —`PaymentMethod`,
`DebtOriginType`, `ExternalObligationType` y `PaymentOrigin`— se declaran en
`src/config/catalogosDominio.js` **con los valores exactos del backend** y una
etiqueta en español para mostrar.

Esto no reintroduce el problema que el trabajo elimina. `mockDb.js` inventaba
filas de negocio —contribuyentes, deudas, pagos— que ocultaban al backend. Un mapa
`CASH → "Efectivo"` es rotulación de presentación sobre valores que sí son del
dominio, de la misma naturaleza que `MODULE_LABELS`. Lo que se corrige es que hoy
los valores del frontend **no coinciden** con los del backend; después coincidirán.

Los catálogos que sí tienen respaldo en la base siguen viniendo por HTTP:
conceptos por `/api/v1/tax-configurations` y reglas de plan por
`/api/v1/payment-plan-configurations`.

## Corrección del medio de pago

`src/services/apiAdapters.js` traduce el enum en ambas direcciones:

```js
const METHOD_TO_API   = { EFECTIVO:"CASH", TARJETA_DEBITO:"CARD", TARJETA_CREDITO:"CARD", TRANSFERENCIA:"TRANSFER", QR:"DIGITAL_WALLET" };
const METHOD_FROM_API = { CASH:"EFECTIVO", CARD:"TARJETA", TRANSFER:"TRANSFERENCIA", DIGITAL_WALLET:"QR" };
```

La ida es lossy: débito y crédito colapsan en `CARD`. La vuelta devuelve
`"TARJETA"`, un valor ausente de `PAYMENT_METHODS`. Los mocks ocultan el defecto
porque el round-trip nunca ocurre; al conectar el backend, un cobro con tarjeta
deja el combo vacío al recargar.

**Decisión**: alinear el frontend a los cuatro valores del backend —Efectivo,
Tarjeta, Transferencia, Billetera/QR— y borrar ambos mapas. Se pierde la
distinción débito/crédito en la interfaz y no se toca la base de datos ni se
agregan migraciones.

## Corrección del origen de la deuda

El frontend aplana dos enums distintos del backend en una sola lista de cuatro
valores:

| Frontend `ORIGIN_TYPES` | Backend |
|---|---|
| `SETTLEMENT` | `DebtOriginType.LIQUIDATION` |
| `PERMIT_FEE`, `COMMERCIAL_FINE`, `TRAFFIC_INFRACTION` | `ExternalObligationType` — **otro enum** |

El campo `originType` de `Debt` sólo acepta `LIQUIDATION` y `EXTERNAL_OBLIGATION`.
El adaptador traduce únicamente `SETTLEMENT → LIQUIDATION`
(`apiAdapters.js:72`), así que filtrar deudas por `PERMIT_FEE` envía un valor que
`FilteredQueryService` rechaza con `INVALID_FILTER_VALUE`. Los mocks lo ocultan
porque filtran sobre su propio campo aplanado.

**Decisión**: el filtro de origen de la pantalla de deudas ofrece los dos valores
reales de `DebtOriginType`. Los tres tipos externos son además códigos de concepto
tributario (`TaxConcept.code`), de modo que quien quiera acotar por M4 o M7 lo hace
por el filtro de concepto, que sí existe en el backend.

## Configuración

Se crea `frontend/.env` (ignorado por git):

```
VITE_API_BASE_URL=http://localhost:8080
VITE_AUTH_MODE=mock
VITE_DEV_IDENTITY_HEADERS=false
```

`VITE_USE_MOCKS` no aparece: al eliminarse el flag del código la variable queda
sin efecto. Se retira también de `frontend/.env.example`, donde hoy sugiere
`true`.

## Pruebas

De los 24 archivos de test del frontend, cinco tocan la capa de mocks:

- `src/services/apiAdapters.test.js` — importa `mockDb` y usa `USE_MOCKS`
- `src/services/refinancing.test.js` — importa `mockDb`
- `src/components/documentos/BillPdfDownload.test.jsx` — usa `USE_MOCKS`
- `src/__tests__/conceptosDesdeCatalogo.test.jsx` — usa `USE_MOCKS`
- `src/services/taxConfigApi.test.js` — usa `USE_MOCKS`

Se adaptan como parte del cambio: las fixtures que hoy salen de `mockDb` pasan a
definirse en el propio test, y las aserciones sobre la rama mock se reemplazan por
aserciones sobre la respuesta HTTP simulada. Los 19 archivos restantes no deberían
requerir cambios.

Criterio de aceptación: `npm test` en verde con los 24 archivos, `npm run build`
exitoso, y `grep -r "mockDb\|USE_MOCKS" src` sin resultados.

Del lado del backend, el endpoint nuevo se cubre con un test de contrato que
verifique el conjunto de valores y la autorización por rol.

## Verificación manual

Con PostgreSQL y el backend levantados, y el frontend en modo real: iniciar sesión
con un usuario demo, registrar un pago con tarjeta, recargar la pantalla y
confirmar que el medio de pago se muestra correctamente —el caso que hoy falla.

## Riesgos

El frontend deja de funcionar sin backend. Es el objetivo del cambio, pero implica
que cualquiera que levante solo el front verá errores de red en vez de una
aplicación navegable. Conviene anotarlo en el README.

Las 99 ramas de `rentasService.js` se eliminan sobre un archivo de 3419 líneas. El
riesgo no es conceptual sino de volumen: conviene avanzar por dominio (pagos,
deudas, planes, exenciones) y correr las pruebas entre cada tramo, en lugar de una
sola pasada.
