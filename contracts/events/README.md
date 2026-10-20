# Eventos de Rentas (M5) — preparación de la primera entrega

Todos los eventos salientes de integración usan el sobre acordado con Core:
`eventId`, `eventType`, `eventVersion`, `occurredAt`, `sourceModule`,
`correlationId`, `causationId` y `data`. El valor de `sourceModule` es `rentas`.
La grafía `occurredAt` sigue el ejemplo JSON del comunicado de M9; su tabla
contiene la variante contradictoria `occuredAt`, pendiente de confirmación.

## Publicaciones preparadas

- `updateTicketStatus` (M2): variantes actualmente emitidas por la interfaz
  `STARTED`, `INFORMATION_REQUIRED`, `RESOLVED` y `REJECTED`.
- `paymentRegistered`, `debtSettled`, `paymentReversed`: hechos económicos
  por imputación, deuda cancelada y reversión respectivamente. Core decidirá
  qué módulo los recibe; M7/M8 aún deben confirmar el contrato común.
- `exemptionRequested`, `updateExemptionStatus`, `paymentPlanRequested`,
  `updatePaymentPlanStatus`, `overdueDebt`.

## Consumo preparado

- `ticketUpdated` de M2 usa el sobre Core y filtra `responsibleAreaId=M5`.
- `permitFeeGenerated` y `commercialFineGenerated` de M4 aceptan el sobre
  Core con `cuit` y `currency`. El mensaje queda registrado, pero no genera
  deuda mientras M5 no tenga una regla acordada de vencimiento; tampoco se
  inventa un contribuyente si el CUIT aún no existe en su proyección local.
- Los adaptadores previos de M1, M7 y M8 siguen disponibles como referencia
  hasta que esos equipos confirmen sus formatos finales.

La primera entrega **no conecta RabbitMQ**: el outbox todavía usa
`local-log`. Los endpoints `/api/v1/dev/integrations/m2/core-events` y
`/api/v1/dev/integrations/m4/core-events` permiten simular recepción local
con el modo de desarrollo habilitado; no están disponibles en producción.
Los registros antiguos con `targetModule=PLATFORM` son internos y no se envían
al publicador de Core; los eventos preparados para Core usan
`targetModule=CORE` únicamente como marca local del outbox.
