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

La tabla `idempotency_request` almacena en la transacción de negocio la identidad autenticada, operación, clave, fingerprint SHA-256 del cuerpo y la respuesta. El alcance usa la tupla usuario + operación (incluye el identificador de ruta cuando corresponde) + clave. La misma tupla y cuerpo, si la primera operación terminó correctamente, devuelve el mismo status del endpoint y el mismo JSON; la misma clave con otro cuerpo devuelve el conflicto `IDEMPOTENCY_KEY_REUSED` (409). El header es opcional por compatibilidad. El claim `INSERT ... ON CONFLICT DO NOTHING` de PostgreSQL serializa reintentos concurrentes; la respuesta y los efectos/outbox se confirman o revierten en una transacción. Si la operación falla y la transacción revierte, no se conserva una reserva ni un payload de error; un reintento vuelve a evaluar la operación. Este comportamiento preserva el rollback y reintento existentes.

### POST cubiertos

El header opcional está disponible en los 66 POST mutables de los controladores de negocio publicados en OpenAPI, incluyendo creación de recursos, efectos económicos y transiciones de estado:

- Configuración tributaria: `/tax-concepts`, `/tax-configurations`, `/tax-configurations/{id}/submit|approve|reject|deactivate`.
- Liquidaciones y corridas: `/liquidations`, `/liquidation-runs`, `/liquidation-runs/{id}/preview|submit|approve|reject|execute`.
- Ajustes, boletas y pagos: `/adjustments`, `/adjustments/{id}/approve|reject`, `/bills`, `/payments`, `/payments/{id}/allocations`, `/electronic-payments`, `/credit-balances/{id}/apply`, `/payments/{id}/reversal-requests`, `/payment-reversals/{id}/approve|reject|execute`.
- Planes: `/payment-plan-configurations`, `/payment-plan-requests`, `/payment-plan-requests/{id}/grant|reject|submit-exception|approve-exception|reject-exception`, `/payment-plans/{planId}/expiration-requests`, `/payment-plan-expirations/{id}/approve|reject`, `/payment-plans/{planId}/refinancing-requests`, `/refinancing-requests/{id}/grant|reject|submit-exception|approve-exception|reject-exception`.
- Exenciones, tickets e integración: `/exemption-requests`, `/exemption-requests/{id}/start-review|request-documentation|documentation|submit-resolution|approve|reject`, `/tickets/{id}/assign|updates|request-information|complete|reject`, `/external-obligations/{id}/retry`, `/integrations/events/{eventId}/reprocess`.
- Reglas de beneficios sociales: `/social-benefit-rules`, `/social-benefit-rules/{id}/submit|approve|disable`.
- Procesamiento fiscal y conciliación: `/debts/{id}/late-charges`, `/administration/process-due-dates`, `/payment-reconciliations/batches`, `/payment-reconciliations/items/{id}/resolve`.
- Conciliación diferida de salientes: `/integrations/deferred/economic/{deferralId}/reconcile`, `/integrations/deferred/exemptions/{requestId}/reconcile` y `/integrations/deferred/payment-plans/{requestId}/reconcile`.

Las operaciones `/bills`, `/payments` y `/electronic-payments` ya contaban con su propio almacenamiento de idempotencia y conservan su comportamiento. En el resto, la nueva envoltura reutiliza V31 y el mismo servicio transaccional.

### POST sin Idempotency-Key

Se excluyen cinco endpoints de cálculo/consulta sin mutaciones:

- `/liquidations/preview` — cálculo previo sin persistir liquidación ni deuda.
- `/electronic-payments/preview` — estimación de pago sin registrar intento ni efecto económico.
- `/debts/{id}/late-charge-preview` — cálculo de recargos sin guardar aplicaciones ni cambiar la deuda.
- `/payment-plans/simulations` — simulación financiera sin crear plan/solicitud.
- `/payment-plans/{planId}/refinancing/simulations` — simulación de refinanciación sin crear solicitud.

`/liquidation-runs/{id}/preview` sí está protegido: aunque se denomina preview, guarda el resumen de cálculo, la configuración/version observada y los resultados/errores de cada ítem en la corrida.

El resto de POST mutables de negocio documentados en OpenAPI está cubierto; los únicos comandos de producción excluidos se enumeran a continuación por tratarse de autenticación/sesiones. No se incorporaron los endpoints ocultos de simulación de eventos DEV.

## Alcance de auditoría

Los endpoints de autenticación `/dev-auth/login`, `/logout`, `/bootstrap` y `/users` no usan `Idempotency-Key`: autentican, revocan sesiones o administran credenciales, y no son comandos económicos/de negocio aptos para guardar/reproducir la respuesta genérica. Las simulaciones de eventos M2/M4 permanecen ocultas y disponibles sólo en DEV; su deduplicación continúa basada en `eventId`, no en una clave HTTP adicional.

La prueba de OpenAPI comprueba que los 66 POST mutables documentados incluyan el header opcional, mientras excluye los cinco cálculos sin escritura y los cuatro endpoints `/dev-auth`. Los controladores de simulación ocultos no forman parte del documento OpenAPI. La protección ETag se implementa sólo para los tres PATCH enumerados, no para cada POST de transición.
