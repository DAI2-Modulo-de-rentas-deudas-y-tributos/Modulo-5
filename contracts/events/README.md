# Eventos de Rentas (M5) — antecedente de la primera entrega

> **Documento histórico.** Los estados, nombres y pendientes descritos debajo
> corresponden a la primera entrega y no sustituyen los contratos aceptados
> posteriormente. En particular, M8 V3 usa `debtOverdue` en lugar del antiguo
> `overdueDebt`; M2 v1.78 y los flujos M1/M4/M7/M8 cuentan con validación
> local. Consultar [estado local consolidado](../../docs/integrations/M5_ESTADO_LOCAL_2026-10-30.md)
> y [contratos de integración vigentes](../../backend/INTEGRATION_CONTRACTS.md).
> La entrega E2E por Core continúa pendiente.

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
  qué módulo los recibe; M4/M7/M8 aún deben confirmar el contrato común.
  El borrador local de IDs UUID persistentes y sus pendientes está en
  [M5_ECONOMIC_OUTBOUND_UUID_DRAFT.md](M5_ECONOMIC_OUTBOUND_UUID_DRAFT.md).
  La [matriz de adaptación M4/M7/M8](M5_ECONOMIC_ADAPTATION_M4_M7_M8_PROPOSAL.md),
  el [schema propuesto](../schemas/m5-economic-outbound.proposed.schema.json)
  y los [JSON de ejemplo](examples/m5-economic/) son material de revisión,
  no contratos aceptados ni publicaciones reales. La prueba local de esas
  propuestas se ejecuta con
  `pwsh -File contracts/tests/Validate-M5EconomicProposal.ps1` desde la raíz.
- `exemptionRequested`, `updateExemptionStatus`, `paymentPlanRequested`,
  `updatePaymentPlanStatus`, `overdueDebt`.

## Consumo preparado

- `ticketUpdated` de M2 usa el sobre Core, filtra `responsibleAreaId=M5` e
  ignora `INFORMATION_REQUIRED`/`INFORMATION_PROVIDED` de reclasificación
  administrativa. El `ticketId` UUID original se conserva como referencia
  externa, sin reemplazar la PK local numérica.
- `permitFeeGenerated` y `commercialFineGenerated` de M4 aceptan el sobre
  Core con `cuit` y `currency`. El mensaje queda registrado, pero no genera
  deuda mientras M5 no tenga una regla acordada de vencimiento; tampoco se
  inventa un contribuyente si el CUIT aún no existe en su proyección local.
- Los adaptadores previos de M1, M7 y M8 siguen disponibles como referencia
  hasta que esos equipos confirmen sus formatos finales.

El adaptador `local-log` sigue siendo el predeterminado y no conecta con Core.
Existe un adaptador RabbitMQ opt-in para brokers aislados/autorizados; sus
publisher confirms sólo prueban aceptación del broker, no entrega a consumidores.
Los endpoints `/api/v1/dev/integrations/m2/core-events` y
`/api/v1/dev/integrations/m4/core-events` permiten simular recepción local
con el modo de desarrollo habilitado; no están disponibles en producción.
Los registros antiguos con `targetModule=PLATFORM` son internos y no se envían
al publicador de Core; los eventos preparados para Core usan
`targetModule=CORE` únicamente como marca local del outbox.
El estado `PUBLISHED` significa únicamente que el adaptador local escribió
en el log; **no prueba entrega ni aceptación por Core o M2**. El código obtiene
el UUID persistente del empleado desde la sesión del backend y guarda por
separado `requestType`, `category` y `subcategory`, pero requiere el esquema
preparado en `backend/migration-proposals/`, fuera de Flyway: no se aplica solo
y no debe desplegarse el código contra la base anterior. La equivalencia entre
el `citizenId` UUID de M2 y el ID numérico de M1 sigue pendiente.
