# Propuesta local: planes de pago M5 → M1

Estado: propuesta técnica no ratificada por M1. No habilita transporte ni implica aceptación del contrato.

M5 conserva sus PK `Long` internas y asigna UUID persistentes propios a `PaymentPlanRequest`, `PaymentPlan` y `Debt`. La solicitud usa el mismo `requestId` desde su creación hasta su resolución; `planId` sólo existe en `GRANTED`. `citizenId` se obtiene exclusivamente de `TaxpayerReference.canonicalCitizenId` para una persona con identidad canónica verificada por M1. No se usa `externalId`, DNI, CUIT ni una PK local como sustituto. Si falta identidad, la operación M5 se completa y la preparación saliente queda diferida.

## Envelope

Se conserva el envelope Core existente de ocho campos: `eventId`, `eventType`, `eventVersion`, `occurredAt`, `sourceModule`, `correlationId`, `causationId`, `data`. Los ejemplos representan el envelope local actual; `eventVersion` se mantiene en `1.0` y no se propone una versión nueva. Correlación y causación son `null` porque no hay un evento fuente autorizado para estos comandos locales. El `eventId` UUID se genera una vez al crear el registro Outbox y permanece estable durante reintentos.

## `paymentPlanRequested`

`requestId`, `citizenId` y cada valor de `debtIds` son UUID. `totalDebt` es el principal consolidado de las deudas elegibles al momento de pedir el plan; `installments` es la cantidad solicitada. Rechazar arreglos de deudas vacíos, monto no positivo y cantidad de cuotas no positiva.

## `updatePaymentPlanStatus`

La resolución comparte el `requestId` y `citizenId` de la solicitud y sólo se prepara después de que la solicitud esté localmente `PUBLISHED` en Outbox. Esa etiqueta sólo indica la ejecución del publisher local, no entrega ni aceptación por Core/M1.

- `GRANTED`: requiere `planId` UUID persistente, `installments` positivos y `totalAmount` positivo.
- `REJECTED`: requiere `reason`; omite `planId`, `installments` y `totalAmount`.

Cada evento usa una clave Outbox idempotente `outbound:<eventType>:PaymentPlanRequest:<requestUuid>`. Reintentar reutiliza el evento existente y su `eventId`; nunca recrea la solicitud ni efectos económicos. Si hay un outbox histórico con forma incompatible (`ppr-<Long>`, `plan-<Long>` o `debt-<Long>`), M5 lo difiere para revisión en vez de reinterpretarlo o enviarlo.

## Reintentos y compatibilidad histórica

V28 agrega UUID a las solicitudes y planes existentes, con backfill único, `NOT NULL` y restricción `UNIQUE`; no altera PK ni relaciones. V25 ya contiene los UUID persistentes de las deudas. La conciliación local `POST /api/v1/integrations/deferred/payment-plans/{requestId}/reconcile` puede preparar los eventos tras recibir una identidad canónica. La respuesta reporta `NOT_CONFIRMED_BY_CORE`; no confirma publicación externa.

Antes de ratificar, M1 debe confirmar el contrato de recepción, el significado de `totalDebt`/`totalAmount`, el ciclo de estados, la clave de idempotencia y cualquier requisito de correlación. Hasta entonces esta integración permanece en el Outbox y no activa Core/RabbitMQ.
