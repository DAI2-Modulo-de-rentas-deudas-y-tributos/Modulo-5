# Contratos de integración de M5 — Rentas

Este documento registra únicamente contratos externos confirmados. M5 deserializa cada contrato en su DTO de módulo, lo valida y recién después lo normaliza/mapea a su modelo interno. Las entidades JPA no se usan como payloads y el adaptador de publicación continúa desacoplado de Kafka, RabbitMQ u otro broker concreto.

## Confirmed contracts

### M1 — Ciudadanos y Organizaciones

M1 es propietario de la identidad de ciudadanos, organizaciones y representaciones. M5 conserva referencias locales; no replica ni administra la identidad canónica.

| Direction | eventType | Main fields | Effect in M5 | Idempotency / business key |
|---|---|---|---|---|
| M1 → M5 | `citizenUpdated` | `eventId`, `occurredAt`, `producer`, `subject`, `data.citizenId`, `updateType`, `details`, `updatedBy` | `REGISTERED` crea/actualiza `TaxpayerReference(CITIZEN)`; `BLOCKED` y `DECEASED` actualizan el estado; `ADDRESS_UPDATED` se audita sin efecto económico | `eventId`; `(CITIZEN, citizenId)` |
| M1 → M5 | `organizationRegistered` | `data.cuit` numérico, `taxId`, `legalName`, `type`, `status`, `holder` | crea/actualiza `TaxpayerReference(ORGANIZATION)` usando `cuit` como referencia externa, nunca como PK local | `eventId`; `(ORGANIZATION, cuit)` |
| M1 → M5 | `representationGranted` | `representationId`, `personId`, `cuit`, `scope`, `from`, `status` | conserva una referencia válida para operaciones futuras | `eventId`; `representationId` |
| M1 → M5 | `representationExpired` | `representationId`, `personId`, `cuit`, `until`, `status` | invalida la referencia sin borrar su historial | `eventId`; `representationId` |
| M5 → M1 | `exemptionRequested` | solicitud, ciudadano, concepto, motivo, porcentaje y vigencia | Outbox en la misma transacción que la solicitud | `OutboxEvent.id`; `requestId` |
| M5 → M1 | `updateExemptionStatus` | estado `APPROVED` o `REJECTED` y campos propios de cada resultado | Outbox en la misma transacción que la resolución | `OutboxEvent.id`; `requestId` |
| M5 → M1 | `paymentPlanRequested` | `requestId`, `citizenId`, `debtIds`, total, cuotas | Outbox; conserva todos los `debtIds` | `OutboxEvent.id`; `requestId` |
| M5 → M1 | `updatePaymentPlanStatus` | estado `GRANTED` o `REJECTED` y campos propios del resultado | Outbox en la misma transacción que la resolución | `OutboxEvent.id`; `requestId` |

Los IDs externos se reciben con su tipo contractual y se convierten a `String` al persistir referencias. Las PK internas de M5 no cambian.

### M2 — Atención Ciudadana

M2 es propietario del estado canónico del ticket. M5 mantiene una proyección para gestionar el caso, pero al responder sólo envía `updateType`: nunca `status`, `newStatus` ni `previousStatus`.

| Direction | eventType | Main fields | Effect in M5 | Idempotency / routing |
|---|---|---|---|---|
| M2 → M5 | `ticketUpdated` | envelope `specVersion`; `data.ticketId`, `citizenId`, `isAnonymous`, `responsibleAreaId`, `updateType`, snapshot/detalles, adjuntos y `updatedAt` | `ROUTED` crea la proyección inicial; `INFORMATION_PROVIDED` registra el aporte; los demás updates confirmados actualizan la proyección | `eventId`; `ticketId`; sólo `responsibleAreaId == M5` |
| M5 → M2 | `updateTicketStatus` | `ticketId`, `updateType`, mensajes, detalles, adjuntos, `updatedBy`, `statusChangedAt` | Outbox; M2 decide la transición canónica | `OutboxEvent.id`; `ticketId` |

Updates outbound permitidos: `STARTED`, `PROGRESS`, `INFORMATION_REQUIRED`, `RETURNED`, `RESOLVED`, `REJECTED`. `RETURNED` es la respuesta normal ante una derivación incorrecta.

Los tickets ajenos a M5 son eventos válidos pero irrelevantes: se marcan `IGNORED`, guardando sólo evidencia técnica mínima (`{}`) y sin crear `TicketCase`, retry ni DLQ. Los tickets anónimos admiten `citizenId = null` de extremo a extremo.

El contrato confirmado de esta etapa detalla `ticketUpdated`. El adapter legado de `ticketCreated` se conserva por compatibilidad, sin inventar un nuevo payload externo.

### M8 — Desarrollo Social

M8 es propietario del estado del beneficio social. M5 conserva una referencia/proyección y separa el `externalStatus` confirmado del estado interno calculado usado en operaciones futuras.

| Direction | eventType | Main fields | Effect in M5 | Idempotency / business key |
|---|---|---|---|---|
| M8 → M5 | `socialBenefitUpdated` | `timestamp`, `sourceModule`, beneficio, ciudadano, programa, array `benefits`, estado y vigencia | `APPROVED` deriva a `ACTIVE`; `REJECTED`, `SUSPENDED` y `FINALIZED` derivan a estados internos no activos; no altera liquidaciones emitidas | `eventId`; `benefitId` |
| M5 → M8 | `overdueDebt` | deuda, ciudadano, concepto, saldo y vencimiento | contrato/outbox preparado; su disparo queda asociado al workflow que determine formalmente el vencimiento | `OutboxEvent.id`; `debtId` |
| M5 → M8 | `debtSettled` | deuda, ciudadano, concepto, fecha y saldo cero | Outbox al cancelar económicamente una deuda | `OutboxEvent.id`; `debtId` |
| M5 → M8 | `exemptionRequested` | mismo payload confirmado con M1 | segundo `OutboxEvent` con `targetModule=M8`, sin duplicar lógica de negocio | `OutboxEvent.id`; `requestId` |
| M5 → M8 | `updateExemptionStatus` | mismo payload confirmado con M1 | segundo `OutboxEvent` con `targetModule=M8`, sin duplicar lógica de negocio | `OutboxEvent.id`; `requestId` |

El payload M8 no informa porcentaje ni conceptos tributarios para `TAX_EXEMPTION`; por eso M5 no inventa una reducción económica automática. Esa decisión requiere un contrato/regla adicional.

### M7 — Tránsito

M7 es propietario de la infracción. M5 no replica el agregado completo: conserva la referencia de negocio y el payload crudo en el log técnico, y crea únicamente la obligación económica y la deuda que le pertenecen.

| Direction | eventType | Main fields | Effect in M5 | Idempotency / business key |
|---|---|---|---|---|
| M7 → M5 | `infractionConfirmed` | envelope `eventId`, `occurredAt`, `sourceModule=transito`; `data.infractionId`, `debtorId`, `debtorIdType`, patente, tipo, fecha/hora, importes, agravantes, inspector y ubicación | normaliza `transito` a `M7`; resuelve al contribuyente por DNI/CUIT; crea `ExternalObligation` y una única `Debt` por `finalAmount`; no crea boleta ni pago | `eventId`; `infractionId` |

`baseAmount`, agravantes y demás detalle propio de la infracción se conservan sólo en `IntegrationEventLog.payload`. La fecha de la infracción se usa como fecha local de exigibilidad porque el modelo económico actual exige `dueDate`; esto es una regla interna explícita, no un campo atribuido al contrato de M7.

Las salidas M5 → M7 (por ejemplo pago, reversión o cancelación de deuda) permanecen `PENDING_EXTERNAL_CONTRACT`: no están confirmados nombres, payloads, routing, ACK ni DLQ y no se inventan en esta etapa.

## Internal normalization

`NormalizedIntegrationEvent` representa internamente `eventId`, `eventType`, `occurredAt`, `sourceModule`, `subject`, payload normalizado y `correlationId` opcional. Se crea después de deserializar el envelope real de M1, M2, M7 o M8.

`ProcessedEvent` y `IntegrationEventLog` usan el `eventId` externo para idempotencia/auditoría. Se conserva además un UUID técnico determinístico sólo para compatibilidad con la PK histórica. El log registra dirección, estado, recepción, procesamiento y error cuando corresponde, sin secretos.

## Outbox and ownership

Todo evento confirmado M5 → M1/M2/M8 se serializa como contrato externo y se guarda como `OutboxEvent` junto con el cambio de dominio. `targetModule` distingue consumidores cuando comparten `eventType`. `EventPublisher` sigue siendo una abstracción; esta etapa no elige ni conecta un broker. No hay contrato outbound confirmado hacia M7.

M5 es propietario de `Debt`, `Payment`, `PaymentPlan`, `Exemption` y demás estado económico local. Ningún consumer accede a bases de otros módulos y no se exponen endpoints REST `/api/v1/events*`.

## Pending contracts

### M4 — PENDING_M4_CONTRACT

PENDING PAYLOAD CONTRACT:

- `TasaHabilitacionGenerada`
- `MultaComercialGenerada`
- `HabilitacionSuspendida`

Se mantienen adapters genéricos existentes. No se agregaron DTOs ni schemas nuevos.

### M7 outbound y otros eventos — PENDING_M7_CONTRACT

Sólo está confirmado el inbound `infractionConfirmed`. Permanecen pendientes cualquier evento de anulación, acarreo, estadía y las notificaciones M5 → M7 relacionadas con pagos, reversiones o deuda saldada.

### Core / JWT / Broker — PENDING_EXTERNAL_CONTRACT

Pendientes: contrato definitivo de identidad/Core/JWT y definición de broker, topics/queues, ACK, retry, DLQ y schemas. Ninguno fue inferido en esta etapa.
