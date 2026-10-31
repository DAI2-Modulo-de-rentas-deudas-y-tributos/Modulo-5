# Contratos de eventos — M5 Rentas y módulos relacionados

**Versión de este inventario:** 25/10/2026. Leer junto con `contratos_originales/`. Este archivo **no reemplaza ni modifica** los contratos oficiales: extrae la interfaz que necesita Rentas, enumera la procedencia y señala incompatibilidades conocidas. **DECISIÓN POSTERIOR DEL RESPONSABLE:** sólo se actualizan los eventos que **M5 EMITE**, primero en código local y después se envía su contrato a consumidores; **los contratos que otros módulos NOS ENVÍAN no se cambian**. Envelope Core siempre inalterado. Ver `06_...` y `07_...`. Los JSON antiguos de §4.2 representan el **estado antes del objetivo UUID**, no el formato final a desarrollar.

## 0. Jerarquía de evidencia (OBLIGATORIA para Codex)

1. **Contrato original del emisor/receptor** con versión y fecha: fuente de los nombres, tipos y reglas **de esa versión**.
2. **Acuerdos explícitos posteriores ratificados por TODOS los consumidores afectados:** pueden ampliar/sustituir el original; solicitar evidencia/versionado.
3. **Decisión de salida M5:** desarrollar primero el objetivo UUID persistente en los eventos propios, probarlo, documentar su versión y distribuirlo luego a consumidores; **NO** equivale a aceptación externa ni habilita activación real prematura.
4. **Código/fixtures locales:** muestran lo implementado, **NO** prueban aceptación por otro módulo.
5. **Log del publicador local:** muestra preparación/escritura de log, **NO** entrega a Core.

**Fuentes en esta entrega:**
- `contratos_originales/M2_Eventos_v1.78_WIP.docx` — Atención Ciudadana, *WIP*; documento más reciente utilizado por M5.
- `contratos_originales/M7_Transito_Core_V4_2026-10-19.pdf` — Tránsito Core V4, vigente 19/10.
- `contratos_originales/M8_Eventos_Desarrollo_Social.pdf` — Desarrollo Social; ejemplos originales con IDs prefijados.
- `contratos_originales/M4_Contrato_Octubre_2026_VERSION_DE_TRABAJO.docx` — Habilitaciones, *versión de trabajo*.
- `contratos_originales/M1_Catalogo_Eventos_v2.1.docx` — Ciudadanos, *v2.1*, anterior a la confirmación de UUID Core requerida; sus ids siguen numéricos.
- `propuestas_no_ratificadas/M5_UUID_SALIENTES_OBJETIVO.md` y `M5_M8_UUID_PROPUESTA.md` — propuestas, **NO** aceptación externa.

## 1. Envelope (ocho campos) del ecosistema Core

```json
{
  "eventId": "8adf1264-d36f-4783-bd75-565978b6e3ac",
  "eventType": "nombreCamelCase",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-25T15:00:00-03:00",
  "sourceModule": "rentas",
  "correlationId": null,
  "causationId": null,
  "data": {}
}
```

- `eventId`: UUID único por hecho; para reintento del **mismo evento** mantener el mismo. Productor M5 genera UUIDv4. Un UUID de *evento* **no es** un UUID de *entidad*.
- `eventType`: nombre camelCase exacto; `eventVersion` actualmente `"1.0"` en flujo local; no aumentarla unilateralmente. `occurredAt`: fecha/hora ISO 8601 con offset.
- `sourceModule`: `ciudadanos` / `atencion-ciudadana` / `habilitaciones` / `rentas` / `transito` / `desarrollo-social` según emisor (otros slugs: `obras`, `ambiente`, `core`). No usar `M5` o `producer` arriba.
- `correlationId`: UUID del hilo, o null donde lo admita Core; **M7 V4 exige correlación en el evento de origen y respuestas con la misma**. En eventos derivados reutilizar correlación real, sin reconstruirla desde IDs Long.
- `causationId`: UUID del evento causal, o null si no hay causalidad. **Pendiente** de verificar con consumidores si necesitan un `causationId` no nulo para cadena pago/cancelación.
- `data`: únicamente campos de negocio; no `targetModule`, `payload`, `producer`, `subject` como extra envelope de M5.
- Entrega futura **at-least-once**, sin orden garantizado; consumidor deduplica `eventId` y maneja datos fuera de orden. Filtros/acks por tipo, área y origen.

## 2. Inventario completo que nos interesa

### ENTRANTES a M5

| Productor | Evento | Uso M5 | Estado del contrato |
|---|---|---|---|
| M1 | `citizenUpdated` | Proyección de persona/estado/domicilio | M1 v2.1 ID numérico, desalineado con UUID canónico |
| M1 | `organizationRegistered` | Organización/CUIT | ID org numérico, `taxId` CUIT string |
| M1 | `representationGranted` | Representante autorizado | IDs numéricos M1 |
| M1 | `representationExpired` | Fin de representación | IDs numéricos M1 |
| M2 | `ticketUpdated` | Tickets dirigidos a Rentas | v1.78 WIP; implementación local validada |
| M4 | `permitFeeGenerated` | Nueva tasa comercial | Octubre 2026, trabajo, ids M4 tipo `FEE-...` |
| M4 | `commercialFineGenerated` | Multa comercial | Octubre 2026, trabajo, ids `FINE-...` |
| M7 | `infractionConfirmed` | Genera deuda por infracción confirmada | Core V4, `infractionId` UUID |
| M8 | `socialBenefitUpdated` | Registrar/actualizar beneficio y aplicar reglas | PDF original + propuesta UUID no ratificada |

### SALIENTES desde M5

| Receptor(es) | Evento | Compartido / sensibilidad |
|---|---|---|
| M2 | `updateTicketStatus` | Contrato M2 v1.78; actualiza estado canónico en M2 |
| M1, M8 | `exemptionRequested` | Un solo evento; pendiente confirmar receptor M1 y tipo UUID M8 |
| M1, M8 | `updateExemptionStatus` | Un solo evento; variante REJECTED no ratificada por M8 |
| M1 | `paymentPlanRequested` | Propuesta M5; **no consta receptor confirmado** en M1 v2.1 |
| M1 | `updatePaymentPlanStatus` | Propuesta M5; **no consta receptor confirmado** en M1 v2.1 |
| M4, M7 | `paymentRegistered` | **Salida M5 a actualizar coherentemente a UUID propio; luego distribuir nuevo contrato a M4/M7** |
| M4, M7, M8 | `debtSettled` | **Salida M5 compartida: preparar formato UUID único y pasarlo a M4/M7/M8; MUNICIPAL_TAX aplazado mientras falten datos válidos** |
| M4, M7 | `paymentReversed` | **Salida M5 a actualizar coherentemente a UUID propio; luego distribuir contrato a M4/M7** |
| M8 | `overdueDebt` | Necesita identidad canónica; aplazar sin ella |

`ticketCreated` existe como M2 → M1, **NO** se consume en la integración oficial M2 → M5.

## 3. Contratos ENTRANTES: detalles por evento

### 3.1 M1 → M5 (fuente: M1 v2.1)

- **`citizenUpdated`**: `data.citizenId` numérico (**contradice objetivo UUID**), `updateType = REGISTERED | BLOCKED | DECEASED | ADDRESS_UPDATED`, `details` según tipo. `REGISTERED` puede incluir `dni`, `cuil`, `name`, `lastname`, `birthdate`, `state`, `nationality`, `address{propertyId,street,number,primary,effectiveFrom}`; otros estados incluyen motivo, fallecimiento o cambio domicilio. `updatedBy` figura en ejemplos como objeto con ID numérico M1.
- **`organizationRegistered`**: ejemplos `organizationId` numérico, `cuit` numérico o `taxId` CUIT string (valores de ejemplo anonimizados), `legalName`, `tradeName`, `type`, `status`, `holder:{personId,personType}`. **No confundir el ID org interno con CUIT**. M4 publica `cuit` y M5 debe buscar correspondencia legítima (no generar identidad M1 nueva).
- **`representationGranted`**: `representationId`, `personId`, `organizationId`, `cuit`, `scope`, `from`, `status=VALID`. IDs numéricos en ejemplos de M1.
- **`representationExpired`**: mismos vínculos relevantes, `until`, `status=EXPIRED`.
- **CONFLICTO ENVELOPE:** M1 v2.1 documenta `producer`, `subject` y `eventId` tipo `M1-EVT-...` en ejemplos. No asumirse Core-compliant; coordinar corrección con M1. **El estado de integración no queda resuelto por convertir números a strings en Rentas.**

### 3.2 M2 → M5: `ticketUpdated` (fuente: M2 v1.78 WIP)

Campos comunes de `data`: `ticketId` UUID; `publicId` string humano; `citizenId` UUID o null (`isAnonymous=true` obliga null); `isAnonymous` boolean; `responsibleAreaId` string; `updateType` enum; `currentStatus`/`currentPriority`; opcionales `progress` entero 0..100, `publicMessage`, `details`, `attachments[]`; `updatedAt` ISO con offset.

- Consumidor **sólo actúa cuando** `responsibleAreaId="M5"` y el tipo amerita gestión. Ignorar en operación `details.informationRequest.purpose=RECLASSIFICATION` y `details.informationResponse.purpose=RECLASSIFICATION`, incluso si área M5. Acknowledge sin guardar contenido de negocio de tickets no dirigidos a M5.
- `ROUTED` contiene **snapshot completo** `details.routing` con **requeridos** `requestType`, `category`, `subcategory`, `ticketType` (`COMPLAINT|REQUEST|INQUIRY|SUGGESTION`), `summary`, `description`; opcionales `formData`, `location`, `resolutionDueAt`, `escalation`, `reassignment`. Si `reassignment=true`, actualizar mismo `ticketId`, **no duplicar**.
- Tipos de update en M2: `STATUS_CHANGED`, `ROUTED`, `PROGRESS`, `PRIORITY_CHANGED`, `ESCALATION_CHANGED`, `INFORMATION_REQUIRED`, `INFORMATION_PROVIDED`, `DUPLICATE_LINKED`, `REOPENED`, `RESOLVED`, `CLOSED`, `CANCELLED`, `CONTENT_UPDATED`.
- `INFORMATION_PROVIDED` debe incluir `details.informationResponse.message` no vacío o attachments no vacíos. `DUPLICATE_LINKED`: `details.duplicate.mainTicketId` UUID.
- Nunca incluir `internalMessage` en un `ticketUpdated` broadcast.

### 3.3 M4 → M5: tasas y multas (fuente: M4 octubre, *trabajo*)

- **`permitFeeGenerated`**: `data={id,permitApplicationId,establishmentId,cuit,amount,currency}`. `id` puede lucir `FEE-001` (string M4), CUIT es un documento de 11 dígitos, `amount` número y `currency` como `ARS`. Debe crear UNA obligación por origen; deduplicar por `eventId` **y por identidad de origen** cuando corresponda.
- **`commercialFineGenerated`**: `data={id,establishmentId,actId,cuit,amount,currency,reason,decidedAt,externalRef,sourceViolationId?,sourceModule?}`. `id` tipo `FINE-001`, **distinto** de `externalRef` (número de disposición). Campos de origen de M6 son condicionales. Crear obligación por multa sin duplicar.
- **Para respuestas M5** usar `originType=PERMIT_FEE|COMMERCIAL_FINE`, `originId=data.id` **exacto**; nunca cambiar a una UUID inventada por M5. Campos exactos M4 consumo de pagos/cancelaciones/reversiones en §4/§5 abajo.

### 3.4 M7 → M5: `infractionConfirmed` (fuente: M7 Core V4, 19/10)

`data={infractionId:UUID,debtorId:string,debtorIdType:"DNI"|"CUIT",licensePlate:string,infractionType:string(enum pendiente catálogo),infractionDateTime:datetime,baseAmount:number,aggravatingFactors:array,finalAmount:number,inspectorId:string,location:{street,crossStreet,latitude,longitude}}`.

- `infractionId`: UUID propiedad de M7 y origen real de las respuestas. `debtorId` **DNI/CUIT STRING**, no UUID, aunque se deba correlacionar con M1 mediante índice verificable.
- Crear obligación con `finalAmount`, deduplicar infracción, preservar correlación original válida en su log de entrada. Envelope M7 V4 exige `correlationId`.

### 3.5 M8 → M5: `socialBenefitUpdated` (fuente: M8 PDF)

`data={benefitId,citizenId,applicationId,programId,programName,benefits:[{type,amount?}],status,reason,startDate,endDate,updatedAt}`.

- Tipos `benefits[].type`: `TAX_EXEMPTION`, `FOOD_ASSISTANCE`, `UTILITY_SUBSIDY`. Sólo el primero puede afectar liquidación tributaria; M5 configura la regla `programId→conceptCode/percentage/validity`, **no lo obtiene del nombre**.
- Estados: `APPROVED` (reason null, fechas vigencia); `REJECTED` (reason, fechas null); `SUSPENDED` (reason, fechas null); `FINALIZED` (reason, `startDate` null, `endDate` puede informar fin). Todos conservan el mismo `benefitId`.
- **El PDF original usa** `ben-00789`, `c-00123`, `sol-00456`, `prog-00789` como strings. La propuesta posterior exige UUID; **pendiente ratificación M8**. M5 debe registrar lo que efectivamente emita M8 según contrato aceptado, no inventar IDs del módulo ajeno.
- Si el ciudadano no puede vincularse autoritativamente con M1, proyección pendiente; sin descuento. Eventos fuera de orden no sobrescriben estado nuevo.

## 4. Contratos SALIENTES: payloads y condiciones

Todos los ejemplos a continuación muestran **la forma de `data`**. El envelope del §1 envuelve cada `data` con `eventType` y `sourceModule="rentas"`. Valores `{…}` representan un registro existente y confirmado, no un valor literal para publicar.

### 4.1 M5 → M2: `updateTicketStatus` (M2 v1.78)

**Campos de `data`:** `ticketId` UUID de M2, `updateType`, `publicMessage?`, `internalMessage?`, `progress?` (0..100), `details?`, `attachments? [{fileName,contentType,url,sizeBytes?}]`, `updatedBy:{type:"EXTERNAL_USER",id:UUID real | type:"SYSTEM",id:null}`, `updateOccurredAt` ISO con offset.

| `updateType` | Condición de negocio / detalles |
|---|---|
| `STARTED` | Iniciar gestión de ticket ROUTED; cambia a IN_PROGRESS en M2 |
| `PROGRESS` | Avance en IN_PROGRESS; debe aportar progreso/mensaje/adjunto útil; STARTED previamente si hace falta |
| `INFORMATION_REQUIRED` | `details.informationRequest.messageForCitizen` obligatorio; `requiredBy?`; máximo una solicitud activa |
| `RETURNED` | `details.returnInfo.reasonCode`: `INVALID_INFORMATION` / `REQUEST_TYPE_MISMATCH` / `INSUFFICIENT_CONTEXT` / `OTHER`; mensaje público o interno requerido |
| `RESOLVED` | `details.resolution.type`: `ACTION_COMPLETED` / `REQUEST_FULFILLED` / `INQUIRY_ANSWERED` / `ACKNOWLEDGED` / `NO_FURTHER_ACTION_REQUIRED`; `publicMessage` no vacío |
| `REJECTED` | `details.cancellation.reasonCode`: `OUT_OF_SCOPE` / `DOES_NOT_APPLY` / `INVALID_REQUEST_TYPE` / `INVALID_DATA` / `REJECTED_BY_AREA` / `OTHER`; mensaje público o interno |

No enviar `newStatus`, `previousStatus` o `responsibleAreaId` dentro de este `data` salvo contrato posterior. M2 es dueño del estado final; no publica espejo `ticketUpdated` por cada acción M5.

### 4.2 Eventos económicos compartidos M5 → M4 / M7 / M8

**`paymentRegistered`** (M4,M7):

```json
{"paymentId":"payment-123","debtId":"debt-456","originType":"TRAFFIC_FINE","originId":"93fd4382-2343-4f3f-a127-9328b29fc520","infractionId":"93fd4382-2343-4f3f-a127-9328b29fc520","amountApplied":50000,"paidAt":"2026-10-25T10:00:00-03:00","remainingBalance":25000}
```

**`debtSettled`** (M4,M7,M8):

```json
{"debtId":"debt-456","citizenId":null,"conceptCode":"TRAFFIC_INFRACTION","originType":"TRAFFIC_FINE","originId":"93fd4382-2343-4f3f-a127-9328b29fc520","infractionId":"93fd4382-2343-4f3f-a127-9328b29fc520","settledAt":"2026-10-25T11:00:00-03:00","remainingBalance":0,"outstandingBalance":0}
```

**`paymentReversed`** (M4,M7):

```json
{"paymentId":"payment-123","debtId":"debt-456","originType":"TRAFFIC_FINE","originId":"93fd4382-2343-4f3f-a127-9328b29fc520","infractionId":"93fd4382-2343-4f3f-a127-9328b29fc520","reversedAmount":50000,"reversedAt":"2026-10-25T12:00:00-03:00","remainingBalance":75000,"reason":"Reversion autorizada"}
```

**ATENCIÓN — JSON DE ESTADO ANTERIOR:** estos tres JSON con `payment-123` / `debt-456` son **ejemplos didácticos de la forma anterior**, NO eventos capturados ni el contrato de salida final deseado. El último supone deuda original 75.000, pago 50.000 y reversión íntegra posterior sin más movimientos; monto/saldo deben venir del ledger real. Aunque M7 V4 tipa `paymentId`/`debtId` como **STRING**, la decisión de M5 es **implementar primero UUID persistentes en sus salidas y luego entregar el nuevo contrato a M4/M7/M8**, con adaptación/versionado posterior. **No cambiar las entradas** de M4/M7 ni el envelope Core. Ver ejemplo objetivo completo en `06_UUID_SALIENTES_ACTUAL_VS_OBJETIVO.md`.

| `originType` | `originId` | `infractionId` | Receptores / cautelas |
|---|---|---|---|
| `PERMIT_FEE` | **M4 `permitFeeGenerated.data.id`** intacto | **Omitido** | M4; el nombre/campos con `citizenId` extra requieren confirmación M4 |
| `COMMERCIAL_FINE` | **M4 `commercialFineGenerated.data.id`** intacto | **Omitido** | M4; no usar `externalRef` como origen |
| `TRAFFIC_FINE` | **M7 `infractionId`** UUID original | **Igual a `originId`**, requerido por V4 | M7; preservar correlación de infracción; `conceptCode` pendiente de acuerdo |
| `MUNICIPAL_TAX` | Propuesta: UUID de liquidación **propia** de M5 | Omitido | M8; `debtSettled` **aplazado** hasta contrato único acordado |

- `paymentRegistered`: por **imputación** de pago a deuda (`amountApplied` y saldo de esa deuda), no importe agregado de caja si hay varias deudas.
- `debtSettled`: únicamente al saldo cero, `remainingBalance=0` y `outstandingBalance=0`; no disparar cancelación por pago parcial.
- `paymentReversed`: cuando reversión efectivamente **ejecutada**, por imputación original de deuda; preservar importe/saldo de la instantánea del hecho, no recalcular desde saldo actual.
- Correlación: para deuda M7 conservar `correlationId` de `infractionConfirmed`; si falta verificar fuente, **DEFERRED**. Para M4 confirmar política original con su equipo; no inferir que M7 define la política de M4.
- `citizenId` de `debtSettled`: M7 lo considera `string|null` opcional; M8 lo ejemplifica como ID ciudadano de texto en su PDF y la propuesta usa UUID; M4 documento de trabajo omite ese campo. **No completar DNI ni Long como si fueran el UUID canónico.**
- Evento `debtSettled` municipal bloqueado hasta acuerdo único sobre `debtId`, `originId`, `citizenId`, nulos, `conceptCode`, otros campos y filtrado de consumidores. No introducir sub-eventos por módulo.

### 4.3 M5 → M8: `overdueDebt`

`data={debtId,citizenId,conceptCode,outstandingAmount,dueDate}`. Ejemplo de estructura:

```json
{"debtId":"503abb3c-f73c-4295-ab86-4fae0fe847b8","citizenId":"2575b9ce-65b8-449f-873b-62ac40328948","conceptCode":"TASA_SERVICIOS","outstandingAmount":85000,"dueDate":"2026-09-20"}
```

Este ejemplo **representa la propuesta UUID**. En producción sólo `debtId` UUID *real persistente M5* y `citizenId` UUID *real canónico M1*; sin este último, conservar deuda y registro aplazado, no evento inválido.

### 4.4 M5 → M1 y M8: `exemptionRequested`

`data={requestId,citizenId,conceptCode,reason,requestedPercentage,requestedFrom,requestedUntil}`.

```json
{"requestId":"034c24df-c322-484f-9d23-489343563dba","citizenId":"2575b9ce-65b8-449f-873b-62ac40328948","conceptCode":"TASA_SERVICIOS","reason":"Situacion socioeconomica","requestedPercentage":50,"requestedFrom":"2026-11-01","requestedUntil":"2027-08-31"}
```

`requestId` UUID persistente M5 (V25), `citizenId` canónico M1. **M8 oficial ejemplifica** `exr-00600` en lugar de UUID; propuesta UUID no ratificada. M1 v2.1 no confirma consumo. Se difiere y luego concilia por Supervisor; no repetir publicaciones.

### 4.5 M5 → M1 y M8: `updateExemptionStatus`

**`APPROVED`**: `data={requestId,citizenId,status:"APPROVED",exemptionId,conceptCode,percentage,validFrom,validUntil}`. **`REJECTED`** propuesto: `{requestId,citizenId,status:"REJECTED",conceptCode,reason}`; no inventar `exemptionId` ni porcentajes de aprobación al rechazar.

- `requestId`: exactamente el mismo que `exemptionRequested`; `exemptionId` UUID persistente M5 si se aprobó. Misma correlación del flujo. Outbox prepara primero solicitud, luego resolución; esto no garantiza llegada ordenada remota.
- **M8 PDF solo ejemplifica APPROVED**; `REJECTED` debe ratificarse. M1 no documenta consumidor en v2.1.

### 4.6 M5 → M1: `paymentPlanRequested`

**Propuesta M5**, no contrato receptor ratificado en M1 v2.1: `data={requestId:UUID M5,citizenId:UUID M1,debtIds:[UUID de deudas M5],totalDebt:number,installments:int}`. Confirmar que el receptor quiera UUID y qué campos/estados requiere.

### 4.7 M5 → M1: `updatePaymentPlanStatus`

**Propuesta M5**, no contrato receptor ratificado en M1 v2.1: `GRANTED`: `{requestId,citizenId,status:"GRANTED",planId,installments,totalAmount}`; `REJECTED`: `{requestId,citizenId,status:"REJECTED",reason}`. `requestId` reutilizado de solicitud. `planId` debe representar plan M5 estable y no UUID nuevo por evento. **No declarar listo sin validar si actualmente existe este UUID persistente de plan**: V25 reportó deuda/liquidación/solicitud/exención, no uno nuevo de plan.

## 5. Estado de aceptación externa: NO inventar acuerdos

| Acuerdo | Evidencia conocida | Decisión de trabajo |
|---|---|---|
| M2 ticket v1.78 | Documento original WIP y pruebas locales | Implementación local, entrega real pendiente |
| M7 V4 pago y reversión | `paymentId`/`debtId` `string`, `originId` e `infractionId` string en respuesta; identidad externa UUID original | Mantener compatibilidad V4; `conceptCode`, `citizenId` y validación bilateral pendientes |
| M4 octubre | Documento «versión de trabajo» | Consumidor real/aceptación de extras y nulos pendiente |
| M8 desarrollo social | PDF con IDs prefijados y sólo status de exención APPROVED | Propuesta UUID y REJECTED pendiente M8 |
| M1 v2.1 | IDs numéricos + envelope distinto; no lista de recepción de exenciones/planes M5 | Resolver identidad canónica/receptores; no inventar mappings |
| Core | Envelope/referencias técnicas; sin transporte real | Conexión en fase final; verificar su spec actual antes de codificar |

## 6. Requisitos de pruebas contractuales mínimos

- Por evento entrante: serializar/deserializar **fixture conforme a original**; payload inválido no produce efecto; `eventId` duplicado no produce doble modificación; otro `eventId` sobre mismo objeto no duplica por identidad de dominio; fuera de orden no pisa estado reciente.
- Por evento saliente: comparar `data`, tipos, required, `null`/omisiones, fechas/moneda/saldos, `correlationId` original y `eventId` estable en reintento.
- Matriz de origen de 3 eventos económicos: `PERMIT_FEE`, `COMMERCIAL_FINE`, `TRAFFIC_FINE` y `MUNICIPAL_TAX` cuando se ratifique. `infractionId` sólo para tráfico.
- `exemptionRequested`→`updateExemptionStatus`: mismo `requestId`, una sola vez, sin estado falsamente entregado; `REJECTED` no inventa `exemptionId`.
- Integración cruzada M2/M7/M8 existente: suite completa backend/frontend antes y después de cualquier cambio en DTO/outbox/pagos.
