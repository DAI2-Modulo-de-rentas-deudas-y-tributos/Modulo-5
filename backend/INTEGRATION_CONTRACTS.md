# Contratos de integración de M5 — Rentas

> **Estado local al 30/10/2026:** las secciones antiguas de este documento son
> antecedentes; los contratos aceptados por M1, M2, M7 y M8 se implementaron y
> probaron localmente. M4 entregó su contrato integral, pero aún debe publicar
> referencias UUID estables; M4 ya confirmó `citizenId: null` para
> organizaciones sin ciudadano canónico asociado.
> La recepción y entrega reales por Core y el vínculo canónico con M1 requieren
> pruebas conjuntas. Ver `../contracts/events/README.md`,
> `../contracts/schemas/common-event-envelope.schema.json` y
> `../docs/integrations/M5_ESTADO_LOCAL_2026-10-30.md`.

Este documento registra únicamente contratos externos confirmados. M5 deserializa cada contrato en su DTO de módulo, lo valida y recién después lo normaliza/mapea a su modelo interno. Las entidades JPA no se usan como payloads y el adaptador de publicación continúa desacoplado de Kafka, RabbitMQ u otro broker concreto.

## Confirmed contracts

### M1 — Ciudadanos y Organizaciones

M1 es propietario de la identidad de ciudadanos, organizaciones y representaciones. M5 conserva referencias locales; no replica ni administra la identidad canónica.

| Direction | eventType | Main fields | Effect in M5 | Idempotency / business key |
|---|---|---|---|---|
| M1 → M5 | `citizenUpdated` | Envelope Core con `eventId`, `eventType`, `eventVersion`, `occurredAt`, `sourceModule`, correlación/causalidad y `data.{citizenId,updateType,details,updatedBy}` | `REGISTERED` crea/actualiza `TaxpayerReference(CITIZEN)`; `BLOCKED` y `DECEASED` actualizan el estado; `ADDRESS_UPDATED` se audita sin efecto económico | `eventId`; `(CITIZEN, citizenId)` |
| M1 → M5 | `organizationRegistered` | `data.cuit` numérico, `taxId`, `legalName`, `type`, `status`, `holder` | crea/actualiza `TaxpayerReference(ORGANIZATION)` usando `cuit` como referencia externa, nunca como PK local | `eventId`; `(ORGANIZATION, cuit)` |
| M1 → M5 | `representationGranted` | `representationId`, `personId`, `cuit`, `scope`, `from`, `status` | conserva una referencia válida para operaciones futuras | `eventId`; `representationId` |
| M1 → M5 | `representationExpired` | `representationId`, `personId`, `cuit`, `until`, `status` | invalida la referencia sin borrar su historial | `eventId`; `representationId` |
| M5 → M1 | `exemptionRequested` | solicitud, ciudadano, concepto, motivo, porcentaje y vigencia | Outbox en la misma transacción sólo si existe UUID canónico M1; si falta, conserva solicitud y registro de aplazamiento | `OutboxEvent.id`; `requestId` UUID persistente |
| M5 → M1 | `updateExemptionStatus` | estado `APPROVED` o `REJECTED` y campos propios de cada resultado | Outbox en la misma transacción sólo si existe UUID canónico M1; si falta, conserva resolución y registro de aplazamiento | `OutboxEvent.id`; `requestId` UUID persistente |
| M5 → M1 | `paymentPlanRequested` | `requestId`, `citizenId`, `debtIds`, total, cuotas | Outbox; conserva todos los `debtIds` | `OutboxEvent.id`; `requestId` |
| M5 → M1 | `updatePaymentPlanStatus` | estado `GRANTED` o `REJECTED` y campos propios del resultado | Outbox en la misma transacción que la resolución | `OutboxEvent.id`; `requestId` |

Los IDs externos se reciben con su tipo contractual y se convierten a `String` al persistir referencias. Las PK internas de M5 no cambian.
El listener de Core despacha los cuatro eventos entrantes M1 anteriores con `sourceModule=ciudadanos` y `occurredAt` correcto; conserva el envelope recibido, incluida correlación y causalidad, en `integration_event_log`. El adaptador legado con `producer` y `subject` permanece separado. Un `citizenId` numérico recibido de M1 sólo identifica la referencia externa local: no se transforma en UUID canónico.

### M2 — Atención Ciudadana

M2 es propietario del estado canónico del ticket. M5 mantiene una proyección para gestionar el caso, pero al responder sólo envía `updateType`: nunca `status`, `newStatus` ni `previousStatus`.

| Direction | eventType | Main fields | Effect in M5 | Idempotency / routing |
|---|---|---|---|---|
| M2 → M5 | `ticketUpdated` | envelope Core (`eventVersion` 1.0); `data.ticketId`, `citizenId`, `isAnonymous`, `responsibleAreaId`, `updateType`, snapshot/detalles, adjuntos y `updatedAt` | `ROUTED` crea o actualiza la proyección; los updates administrativos `RECLASSIFICATION` se ignoran; M2 conserva el estado canónico | `eventId`; `ticketId`; sólo `responsibleAreaId == M5` |
| M5 → M2 | `updateTicketStatus` | `ticketId`, `updateType`, mensajes, detalles, adjuntos, `updatedBy`, `updateOccurredAt` | Outbox local; M2 decide la transición canónica cuando exista transporte real | `OutboxEvent.id`; `ticketId` |

Updates outbound permitidos: `STARTED`, `PROGRESS`, `INFORMATION_REQUIRED`, `RETURNED`, `RESOLVED`, `REJECTED`. `RETURNED` es la respuesta normal ante una derivación incorrecta.

Los tickets ajenos a M5 son eventos válidos pero irrelevantes: se marcan `IGNORED`, guardando sólo evidencia técnica mínima (`{}`) y sin crear `TicketCase`, retry ni DLQ. Los tickets anónimos admiten `citizenId = null` de extremo a extremo.

El contrato confirmado de esta etapa detalla `ticketUpdated`. El adapter legado de `ticketCreated` se conserva por compatibilidad, sin inventar un nuevo payload externo.

**Despliegue M2 v1.78:** `DemoUser.integrationActorId` es un UUID persistente del empleado que el backend obtiene al resolver la sesión; no se acepta del frontend. `ROUTED` conserva `requestType`, `category` y `subcategory` por separado. La migración versionada está en `src/main/resources/db/migration-postgresql/V24__m2_v1_78_identity_and_routing.sql` y se incluye en Flyway tanto en el perfil local como en el productivo. El SQL conserva exactamente el contenido probado en las bases aisladas; su encabezado de prueba se dejó intacto para preservar el checksum de Flyway. Al desplegar sobre una base anterior, respaldar y aplicar V22–V24 antes de usar este mapeo JPA. V24 asigna una sola vez los UUID de usuarios anteriores, deja la PK `BIGINT` intacta y recupera clasificación histórica únicamente de snapshots `ROUTED` oficiales cuando hay datos suficientes. El UUID `citizenId` de M2 tampoco puede vincularse automáticamente al ID numérico del contrato M1 actual: se espera una equivalencia autoritativa, nunca una identidad inventada.

`PROGRESS` y `RETURNED` son variantes válidas del contrato, pero M5 todavía no tiene casos de uso ni controles de interfaz para avance operativo o devolución por tipo incorrecto. No se emitirán hasta definir esos flujos. La reasignación `ROUTED` conserva el mismo `ticketId` y restablece la asignación local.

Las acciones locales marcan una intención operativa en M5, no una aceptación de M2: sin transporte y confirmación real, la proyección local puede diferir del estado canónico. La conciliación de estados tras entrega/rechazo de Core y M2 queda pendiente.

### M8 — Desarrollo Social

M8 es propietario del estado del beneficio social. M5 conserva una referencia/proyección y separa el `externalStatus` confirmado del estado interno calculado usado en operaciones futuras. El consumidor oficial lee el envelope de Core (`eventId`, `eventType`, `eventVersion`, `occurredAt`, `sourceModule`, `data`) y conserva los UUID de M8 (`benefitId`, `citizenId`, `applicationId`, `programId`). El adaptador anterior se mantiene separado para eventos locales legados.

| Direction | eventType | Main fields | Effect in M5 | Idempotency / business key |
|---|---|---|---|---|
| M8 → M5 | `socialBenefitUpdated` | Envelope Core; `data.benefitId`, `citizenId`, `applicationId`, `programId`, `programName`, `benefits`, `status`, `reason`, fechas y `updatedAt` | Los cuatro estados se persisten con trazabilidad. Eventos repetidos o anteriores no reemplazan el estado reciente; no altera liquidaciones emitidas | `eventId`; `benefitId` |
| M5 → M8 | `debtOverdue` | `debtId` UUID persistente M5, `citizenId` UUID canónico M1, concepto, saldo y vencimiento | El proceso de vencimientos genera outbox sólo con identidad canónica; en caso contrario registra el aplazamiento sin perder la deuda | `OutboxEvent.id`; deuda Long interna |
| M5 → M8 | `debtSettled` | deuda, ciudadano, origen `MUNICIPAL_TAX`, fecha y saldo cero | La cancelación municipal prepara el contrato compartido sólo con UUID canónico M1 verificado; sin él queda aplazada. M4 y M7 conservan sus reglas por origen | deuda Long interna |
| M5 → M8 / M1 | `exemptionRequested` | `requestId` UUID persistente M5 y `citizenId` UUID canónico M1 | Un único evento hacia Core sólo cuando existe identidad canónica; sin ella se conserva la solicitud y se registra el aplazamiento | solicitud Long interna |
| M5 → M8 / M1 | `updateExemptionStatus` | `requestId` y, al aprobar, `exemptionId` UUID persistentes M5; `citizenId` UUID canónico M1 | Un único evento hacia Core sólo con identidad canónica y datos obligatorios de la variante; sin ellos se conserva la resolución y se registra el aplazamiento | solicitud Long interna |

El payload M8 no informa porcentaje ni conceptos tributarios para `TAX_EXEMPTION`; por eso M5 no inventa una reducción económica automática. Una regla local propuesta por Rentas y aprobada por un Supervisor vincula `programId`, código de tributo, porcentaje y vigencia; queda versionada y auditada. Sólo un beneficio `APPROVED` con vínculo canónico M1 confiable y regla aprobada vigente puede reducir una liquidación nueva. El importe original, descuento, beneficio y versión de regla quedan en los componentes de la liquidación. Se usa el mayor porcentaje aplicable, no se suman exenciones; `FOOD_ASSISTANCE` y `UTILITY_SUBSIDY` no reducen tributos. Si el neto es cero, no se crea deuda cobrable. Los beneficios sin equivalencia canónica M1 quedan pendientes de vinculación.

V25 y V26 ya fueron validadas y aplicadas a la base local `rentas`. V25 conserva las claves primarias `BIGINT` y asigna UUID persistentes sólo a entidades de M5; `taxpayer_reference.canonical_citizen_id` permanece nulo hasta recibir una correspondencia autoritativa de M1. V26 agrega historial de eventos M8, reglas y procedencia de descuentos. No se vuelven a ejecutar estas migraciones como parte de la adaptación de eventos. Los DTOs de `debtOverdue`, `exemptionRequested` y `updateExemptionStatus` usan los UUID persistidos, pero un evento sin identidad M1 no entra al outbox. `debtOverdue` se reevalúa en la siguiente corrida de vencimientos. La entrega real de `debtSettled` por Core y el filtrado por origen en los consumidores continúan pendientes de prueba conjunta.

La conciliación local de exenciones no requiere otra migración: V21 ya provee `integration_event_log.business_key` único y V22 provee `outbox_event.idempotency_key` único. El aplazamiento persistente es un registro interno `OUTBOUND/PENDING` con `phase=DEFERRED`, no un evento Core. Cuando exista una vinculación M1 confiable en `taxpayer_reference.canonical_citizen_id`, un Supervisor puede ejecutar `POST /api/v1/integrations/deferred/exemptions/{requestId}/reconcile`. El servicio bloquea la solicitud, verifica la identidad, prepara primero `exemptionRequested` y después `updateExemptionStatus` si la decisión es final. Cada evento preparado usa una clave idempotente; un outbox histórico incompatible exige revisión manual. El publicador local aparta como `DEAD_LETTER` los eventos históricos de exenciones cuyos identificadores no sean UUID válidos, sin publicarlos. Los intentos y el resultado quedan auditados. El registro de aplazamiento pasa a `PROCESSED` con `phase=OUTBOX_PREPARED` y el ID del outbox sólo después de preparar el evento. En la respuesta, `deliveryStatus=NOT_CONFIRMED_BY_CORE` aun si el adaptador local marca el outbox `PUBLISHED`: eso sólo significa procesamiento por `local-log`, no entrega ni aceptación externa. El publicador local no procesa una resolución antes de que la solicitud esté marcada `PUBLISHED` localmente. El ACK real, el orden extremo a extremo y la conciliación con M1/Core seguirán pendientes hasta implementar el transporte.

El nombre `overdueDebt` corresponde al contrato histórico M8 V2; el nombre vigente V3 es `debtOverdue`. La variante para organizaciones no está confirmada. M5 sólo prepara este evento para referencias `CITIZEN` vinculadas a un UUID canónico de M1; organizaciones y ciudadanos sin ese vínculo quedan aplazados, sin enviar un identificador supuesto.

### M7 — Tránsito

M7 es propietario de la infracción. M5 no replica el agregado completo: conserva la referencia de negocio y el payload crudo en el log técnico, y crea únicamente la obligación económica y la deuda que le pertenecen.

| Direction | eventType | Main fields | Effect in M5 | Idempotency / business key |
|---|---|---|---|---|
| M7 → M5 | `infractionConfirmed` Core V4 | envelope `eventId`, `correlationId`, `occurredAt`, `sourceModule=transito`; `data.infractionId`, `debtorId`, `debtorIdType`, patente, tipo, fecha/hora, importes, agravantes, inspector y ubicación | normaliza `transito` a `M7`; resuelve al contribuyente por DNI/CUIT; crea `ExternalObligation` y una única `Debt` por `finalAmount`; no crea boleta ni pago | `eventId`; `infractionId` |

`baseAmount`, agravantes, `correlationId` y demás detalle propio de la infracción se conservan en `IntegrationEventLog.payload`. `ExternalObligation.sourceEventId` enlaza la obligación con ese registro. La fecha de la infracción se usa como fecha local de exigibilidad porque el modelo económico actual exige `dueDate`; esto es una regla interna explícita, no un campo atribuido al contrato de M7.

Para `paymentRegistered`, `debtSettled` y `paymentReversed` relacionados con una infracción oficial se recupera el `correlationId` original mediante ese enlace. Si el log histórico falta, no contiene un UUID válido o no coincide con el `infractionId`, se conserva la operación económica pero se aplaza el evento en `integration_event_log` con `M7_ORIGINAL_CORRELATION_ID_UNAVAILABLE`; no se sustituye por un UUID derivado de una PK. Esos casos requieren revisión de la evidencia original antes de cualquier conciliación. No se requiere una columna nueva para eventos oficiales recibidos con V4.

### M5 → M4 / M7 — application-level confirmed behavior

Cuando una imputación afecta una deuda cuya `ExternalObligation` concreta proviene de M4 o M7, M5 guarda en el transactional outbox:

| Origen | eventType | Payload económico | Referencia conservada |
|---|---|---|---|
| M4 | `paymentRegistered` | `paymentId`, `debtId`, `originType`, `originId`, `amountApplied`, `paidAt`, `remainingBalance` | `originId = ExternalObligation.externalReferenceId` |
| M4 | `debtSettled` | `debtId`, `citizenId`, `conceptCode`, `originType`, `originId`, `settledAt`, saldos cero | `originId = ExternalObligation.externalReferenceId` |
| M4 | `paymentReversed` | mismos identificadores, `reversedAmount`, `reversedAt`, `remainingBalance`, `reason` | `originId = ExternalObligation.externalReferenceId` |
| M7 | `paymentRegistered` | como M4 y `infractionId` | `originId = infractionId` original de M7 |
| M7 | `debtSettled` | como M4 y `infractionId` | `originId = infractionId` original de M7 |
| M7 | `paymentReversed` | como M4 y `infractionId` | `originId = infractionId` original de M7 |

Los eventos económicos externos se preparan con `targetModule=CORE`; `originType` y `originId` conservan el origen M4/M7. Una imputación parcial prepara `paymentRegistered`; `debtSettled` externo se prepara únicamente cuando la deuda llega efectivamente a `PAID`. La preparación local usa UUID persistentes M5: `payment.integration_id` (V27) y `debt.integration_id` (V25). Los antiguos `payment-<Long>` y `debt-<Long>` se retienen para revisión si aparecen en outbox o snapshots históricos; no se publican como contrato nuevo. El `citizenId` de deuda externa sólo se informa si existe UUID canónico M1, sin inventar una identidad a partir del ID local. `../contracts/events/M5_ECONOMIC_OUTBOUND_UUID_DRAFT.md` conserva la propuesta anterior como antecedente, no como estado actual de aceptación. M8 aceptó `originType=MUNICIPAL_TAX`: `debtSettled` municipal se prepara con ciudadano canónico verificado y se aplaza si falta esa identidad. V25 y V27 estaban aplicadas correctamente en la última comprobación exitosa de `rentas`; este documento no acredita una consulta nueva.

Las cuotas nuevas registran transaccionalmente en la auditoría de la imputación la distribución exacta del capital por deuda; de allí se obtienen los importes de los eventos y de una reversión posterior. Los pagos históricos de cuotas anteriores a este registro no tienen una distribución confiable por pago: se conserva la reversión económica anterior, pero la notificación externa se aplaza con `LEGACY_PLAN_DISTRIBUTION_UNAVAILABLE` para revisión; no se inventa un importe por deuda. Los nuevos eventos económicos usan claves de idempotencia por imputación y deuda en el outbox. El orden de preparación local no confirma orden de entrega por Core.

El ejemplo V4 de `debtSettled` usa `conceptCode=TRAFFIC_FINE`, pero no declara un enum ni una regla que obligue a ese valor. M5 conserva `TRAFFIC_INFRACTION` hasta acordar el catálogo con M7. `citizenId` es opcional en V4; M5 sólo lo incluye si conoce el UUID canónico M1. Broker, topics/queues, ACK y DLQ: `PENDING_EXTERNAL_CONTRACT`.

## Internal normalization

`NormalizedIntegrationEvent` representa internamente `eventId`, `eventType`, `occurredAt`, `sourceModule`, `subject`, payload normalizado y `correlationId` opcional. Se crea después de deserializar el envelope real de M1, M2, M7 o M8.

`ProcessedEvent` y `IntegrationEventLog` usan el `eventId` externo para idempotencia/auditoría. Se conserva además un UUID técnico determinístico sólo para compatibilidad con la PK histórica. El log registra dirección, estado, recepción, procesamiento y error cuando corresponde, sin secretos.

## Outbox and ownership

Todo evento preparado M5 → M1/M2/M4/M7/M8 se serializa y se guarda como `OutboxEvent` junto con el cambio de dominio. `targetModule` distingue consumidores cuando comparten `eventType`. `EventPublisher` sigue siendo una abstracción y `LocalLogEventPublisher` sólo registra el payload en logs: el estado `PUBLISHED` del outbox local **no confirma entrega ni aceptación por Core o M2**. La entrega real, suscripción, acuse y política de reintentos del broker están pendientes.

M5 es propietario de `Debt`, `Payment`, `PaymentPlan`, `Exemption` y demás estado económico local. Ningún consumer accede a bases de otros módulos y no se exponen endpoints REST `/api/v1/events*`.

## Pending contracts

### M4 — contrato integral recibido; pendientes de datos e interoperabilidad

`permitFeeGenerated` y `commercialFineGenerated` se consumen localmente con
DTOs específicos. El contrato integral recibido reemplaza, para estos flujos,
los nombres tentativos `TasaHabilitacionGenerada` y `MultaComercialGenerada`
del material histórico. M4 confirmó `citizenId: null` en `debtSettled` para
organizaciones sin ciudadano canónico asociado; M5 conserva el UUID original
`data.id` como `originId` y su propio UUID persistente como `debtId`. Si hay
UUID canónico verificado, lo informa. Un ciudadano sin ese vínculo permanece
aplazado. M4 aún debe suministrar UUID persistentes para las referencias de
origen; los plazos concretos y la entrega E2E siguen pendientes. Ver
`../docs/integrations/M5_M4_VALIDACION_LOCAL_2026-10-30.md`.

### M7 outbound y otros eventos — Core V4 local

Además de `infractionConfirmed`, los payloads locales de `paymentRegistered`, `debtSettled` y `paymentReversed` se contrastan con Core V4 y las aclaraciones posteriores de M7. La recepción real, los casos históricos sin correlación recuperable y el transporte por Core siguen pendientes de prueba conjunta. No se modifica el payload compartido para resolver pendientes de M4.

### Core / JWT / Broker — PENDING_EXTERNAL_CONTRACT

Pendientes: claim final de identidad M1/Core/JWT, registro y prueba real de publicaciones y suscripciones, configuración del broker y comprobación E2E de routing, ACK, retry y DLQ. Core comunicó `POST /api/v1/events`, respuestas 202/200 duplicate/422 y recuperación de DLQ; esas aclaraciones están documentadas localmente, pero no acreditan que el transporte de M5 esté conectado ni que otro módulo haya consumido los eventos.
