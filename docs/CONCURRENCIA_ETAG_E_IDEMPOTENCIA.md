# Concurrencia e idempotencia HTTP

## SCRUM-810: ETag e If-Match

El backend publica ETag en el GET de detalle y acepta `If-Match` en estos tres recursos que tienen PATCH:

| GET / PATCH | Versión utilizada | Protección al actualizar |
|---|---|---|
| `/api/v1/tax-concepts/{id}` | `lock_version` JPA, distinto del código de concepto | `SELECT ... FOR UPDATE`, comparación bajo lock y `@Version` |
| `/api/v1/tax-configurations/{id}` | `lock_version` JPA, distinto de la versión funcional de configuración | `SELECT ... FOR UPDATE`, comparación bajo lock y `@Version` |
| `/api/v1/payment-plan-configurations/{id}` | versión funcional copy-on-write existente | lock de la secuencia, comparación del ETag y rechazo si dejó de ser la versión vigente |

Los tags son opacos, fuertes y específicos del recurso. El cliente debe conservar el valor de `ETag` y enviarlo sin alteraciones en `If-Match`. Si se omite el header, la actualización se admite por compatibilidad con clientes existentes; esa llamada no obtiene protección contra una edición obsoleta. Si se envía un valor obsoleto o desconocido, la respuesta es `412 application/problem+json` y no se ejecuta la modificación ni se genera Outbox. PATCH devuelve el ETag nuevo. La migración incremental V31 incorpora sólo los contadores técnicos y la tabla de idempotencia; no reescribe migraciones anteriores.

Los demás POST que cambian estados o ejecutan acciones no reciben precondición ETag en esta entrega. El inventario incluye: envío/aprobación/rechazo/desactivación de configuraciones; envío/aprobación/rechazo/ejecución de corridas; aprobación/rechazo de ajustes; aprobación/rechazo/ejecución de reversiones; otorgamiento/rechazo/excepción de planes; aprobación/rechazo de caducidades; aprobación/rechazo de refinanciaciones; transiciones de exenciones y tickets; reintento de obligaciones externas; procesamiento fiscal y conciliación. Son comandos de transición y no PATCH/PUT; deben evaluarse por ticket si sus contratos exigen precondiciones explícitas.

## SCRUM-811: Idempotency-Key

La nueva tabla `idempotency_request` almacena en la transacción de negocio la identidad autenticada, operación, clave, fingerprint SHA-256 del cuerpo y la respuesta. El alcance usa la tupla usuario + operación (incluye el identificador de ruta cuando corresponde) + clave. La misma tupla y cuerpo devuelve el mismo status del endpoint y el mismo JSON; la misma clave con otro cuerpo devuelve el conflicto `IDEMPOTENCY_KEY_REUSED` (409). El header es opcional por compatibilidad. El claim `INSERT ... ON CONFLICT DO NOTHING` de PostgreSQL serializa reintentos concurrentes; la respuesta y los efectos/outbox se confirman o revierten en una transacción.

### POST cubiertos

- `POST /api/v1/liquidations` — creación de liquidación individual.
- `POST /api/v1/payment-plan-requests` — solicitud de plan.
- `POST /api/v1/payment-plan-requests/{id}/grant` — otorgamiento de plan.
- `POST /api/v1/exemption-requests` — creación de solicitud de exención.
- `POST /api/v1/payments/{id}/reversal-requests` — solicitud de reversión.
- `POST /api/v1/payment-plans/{planId}/refinancing-requests` — solicitud de refinanciación.
- `POST /api/v1/adjustments` — creación de ajuste.

Además, estos POST ya soportaban idempotencia antes de esta entrega y conservan su implementación y almacenamiento: `/api/v1/bills`, `/api/v1/payments` y `/api/v1/electronic-payments`. OpenAPI documenta el header en los siete endpoints nuevos y los tres existentes.

### Fuera del alcance de Idempotency-Key

No se afirma cobertura de los demás POST. En particular, quedan fuera las previsualizaciones, procesos por lotes, operaciones de aplicación de saldos, imputaciones, pagos contra endpoints distintos del registro de pago, solicitudes de caducidad, transiciones de rechazo/aprobación (salvo `grant`), reintentos de integración y conciliaciones. Estos comandos conservan sus validaciones e idempotencia interna previa cuando exista, pero no adquieren una garantía HTTP genérica por esta tarea.

## Alcance de auditoría

El inventario de rutas mutables puede reproducirse con `rg -n "@(Put|Patch|Post)Mapping" backend/src/main/java`. La protección ETag se implementa sólo para los tres PATCH enumerados, no para cada POST de transición. La protección Idempotency-Key es explícita sólo para los diez POST enumerados como cubiertos.
