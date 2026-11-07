# Concurrencia e idempotencia HTTP

## SCRUM-810: ETag e If-Match

ETag se publica en los detalles modificables o usados como precondición:

| Recurso | Representación del ETag | Operaciones con If-Match opcional |
|---|---|---|
| /tax-concepts/{id} | lock_version JPA | PATCH |
| /tax-configurations/{id} | lock_version JPA | PATCH, submit, approve, reject, deactivate |
| /payment-plan-configurations/{id} | versión funcional copy-on-write | PATCH |
| /debts/{id} | hash de la representación | late-charges; allocations que afectan la deuda |
| /liquidations/{id} | hash del detalle | Sólo lectura; no hay modificación individual por id |
| /payment-plans/{id} | hash de la representación | allocations de cuotas, expiración y refinanciación; aprobaciones/grants correspondientes |
| /exemption-requests/{id} | hash de la representación | start-review, request-documentation, documentation, submit-resolution, approve, reject |
| /liquidation-runs/{id} | hash de corrida e ítems | preview, submit, approve, reject, execute |
| /payment-plan-requests/{id} | hash de solicitud | grant, reject y transiciones de excepción |

Los tags son opacos, fuertes y específicos del recurso. El cliente debe conservar el valor de ETag y enviarlo sin alteraciones en If-Match. Si se omite el header, se mantiene compatibilidad con los clientes existentes, pero la llamada no queda protegida frente a una lectura obsoleta. Un tag obsoleto o desconocido devuelve HTTP 412 application/problem+json con PRECONDITION_FAILED; la operación y sus eventos se revierten. Las comparaciones se realizan bajo los bloqueos de fila existentes. Cuando una acción cambia el recurso, la respuesta incluye el ETag actualizado.

En las operaciones POST que aceptan Idempotency-Key, omitir If-Match conserva el fingerprint previo. Si se envía, el tag integra el fingerprint: repetir la misma clave con otro tag se rechaza como IDEMPOTENCY_KEY_REUSED (409). El replay idéntico devuelve el resultado almacenado sin repetir los efectos.

La migración incremental V31 incorpora los contadores técnicos y la tabla de idempotencia; no reescribe migraciones anteriores.

No todas las operaciones mutables tienen If-Match. El alcance de esta entrega excluye, entre otras, ajustes, reversiones, transiciones de tickets, reintentos de obligaciones externas, procesamiento fiscal y conciliación. Esas operaciones conservan sus bloqueos e idempotencia existentes y requieren decisión/criterios específicos antes de incorporar precondiciones.
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

La prueba de OpenAPI comprueba que los 66 POST mutables documentados incluyan el header opcional, mientras excluye los cinco cálculos sin escritura y los cuatro endpoints `/dev-auth`. Los controladores de simulación ocultos no forman parte del documento OpenAPI. ETag/If-Match se aplica a los PATCH de la tabla y al subconjunto de acciones enumeradas arriba; no cubre todos los POST mutables.
