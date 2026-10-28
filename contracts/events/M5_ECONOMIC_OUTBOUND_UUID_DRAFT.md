# M5 → Core: eventos económicos compartidos (borrador local)

Estado al 26/10/2026: **objetivo M5 aprobado, preparación local, no distribuido ni aceptado por consumidores**. V27 pasó en un PostgreSQL aislado y la suite backend pasó 417/417 con Docker; V27 **no** se aplicó a `rentas`. No habilitar transporte Core/RabbitMQ con este documento sin acordar versión, filtrado e identidad con M4, M7, M8 y Core. Este borrador sólo describe salidas M5; no cambia contratos entrantes ajenos.

## Envelope común

Los tres eventos usan exactamente `eventId` (UUIDv4), `eventType` (string), `eventVersion` (string), `occurredAt` (ISO 8601 con zona), `sourceModule` (`rentas`), `correlationId` (UUID o null), `causationId` (UUID o null) y `data` (objeto). El outbox conserva el mismo `eventId` al reintentar. El valor local actual de `eventVersion` es `1.0`; su valor para interoperar con el cambio de IDs **requiere confirmación de Core y consumidores**.

### Versionado y transición: decisión pendiente de Core

Pasar de `payment-<Long>`/`debt-<Long>` a UUID conserva el tipo JSON `string`, pero **rompe la semántica y el formato** que algunos receptores podrían haber persistido o validado. También cambia la presencia efectiva de `citizenId`. Por eso, `eventVersion="1.0"` en el código y en los ejemplos **no demuestra compatibilidad**. Si Core y los tres receptores confirman que el contrato anterior nunca se activó, podrían ratificar esta forma como versión inicial; si ya hay consumidores activos, se necesita una revisión/versionado y una ventana de transición acordados por Core, sin escoger aquí un número nuevo unilateralmente. No publicar dos veces el mismo movimiento con dos IDs ni convertir un evento histórico a UUID cambiando sólo su JSON: conservar idempotencia, evidencia y trazabilidad. Outbox y snapshots históricos con IDs prefijados quedan en revisión manual.

La secuencia propuesta es: inventario de receptores y datos históricos → acuerdo de `eventVersion`, aceptación de nulos/omisiones y estrategia de corte → adaptación y pruebas de M4/M7/M8 → habilitación gradual en Core. El envelope y los nombres de `eventType` permanecen intactos.

| Evento | Consumidores previstos | Hecho y clave de idempotencia local |
|---|---|---|
| `paymentRegistered` | M4, M7 | Una imputación por deuda; `PaymentAllocation` + `Debt`. No usa el total agregado de caja. |
| `debtSettled` | M4, M7, M8 | Sólo cuando el saldo de esa deuda llega a cero; una vez por imputación/deuda. El caso municipal continúa diferido. |
| `paymentReversed` | M4, M7 | Sólo al ejecutar la reversión; conserva monto y saldo del movimiento, no los recalcula al conciliar. |

## Campos de `data`

`paymentRegistered`: `paymentId` UUID M5 requerido, `debtId` UUID M5 requerido, `originType` requerido, `originId` string requerido, `infractionId` string requerido sólo con `TRAFFIC_FINE` (omitido para M4), `amountApplied` decimal positivo requerido, `paidAt` datetime requerido, `remainingBalance` decimal no negativo requerido.

`debtSettled`: `debtId` UUID M5 requerido, `citizenId` UUID canónico M1 **opcional y omitido sin vínculo verificado**, `conceptCode` string requerido, `originType` requerido, `originId` string requerido, `infractionId` como arriba, `settledAt` datetime requerido, `remainingBalance` y `outstandingBalance` decimal cero requeridos.

`paymentReversed`: `paymentId`, `debtId`, `originType`, `originId`, `infractionId` con las mismas reglas; `reversedAmount` decimal positivo requerido, `reversedAt` datetime requerido, `remainingBalance` decimal no negativo requerido, `reason` string requerido.

En JSON los UUID y las fechas se serializan como `string`; los importes y saldos son `number` decimales. `Requerido` significa que la clave debe aparecer y **no** puede ser `null`. Las claves opcionales indicadas abajo se **omiten**, no se envían como `null`.

| Campo | Eventos | Tipo JSON | Presencia | Fuente y regla |
|---|---|---|---|---|
| `paymentId` | `paymentRegistered`, `paymentReversed` | `string` UUID | Requerido | `payment.integration_id` persistente de M5 (V27). |
| `debtId` | Los tres | `string` UUID | Requerido | `debt.integration_id` persistente de M5 (V25). |
| `originType` | Los tres | `string` enum | Requerido | `PERMIT_FEE`, `COMMERCIAL_FINE` o `TRAFFIC_FINE`; `MUNICIPAL_TAX` se propone sólo para `debtSettled`. |
| `originId` | Los tres | `string` no vacío | Requerido | ID externo original, sin conversión ni prefijos M5. |
| `infractionId` | Los tres | `string` UUID | Condicional | Requerido sólo para `TRAFFIC_FINE`, igual a `originId`; omitido para M4. |
| `amountApplied` | `paymentRegistered` | `number` decimal | Requerido | Imputación positiva a **esta** deuda, no pago total de caja. |
| `paidAt` | `paymentRegistered` | `string` date-time con zona | Requerido | Fecha/hora efectiva del pago. |
| `reversedAmount` | `paymentReversed` | `number` decimal | Requerido | Imputación positiva revertida de **esta** deuda. |
| `reversedAt` | `paymentReversed` | `string` date-time con zona | Requerido | Fecha/hora de la reversión. |
| `reason` | `paymentReversed` | `string` no vacío | Requerido | Motivo de reversión. |
| `remainingBalance` | Los tres | `number` decimal | Requerido | Saldo no negativo luego del movimiento; `0` en `debtSettled`. |
| `outstandingBalance` | `debtSettled` | `number` decimal | Requerido | `0` al cancelar la deuda. |
| `settledAt` | `debtSettled` | `string` date-time con zona | Requerido | Fecha/hora de la cancelación. |
| `conceptCode` | `debtSettled` | `string` no vacío | Requerido | Código de concepto M5; catálogos específicos M4/M7 pendientes de ratificación. Los valores de ejemplos no fijan esos catálogos. |
| `citizenId` | `debtSettled` | `string` UUID | Condicional | Externo M4/M7: opcional, sólo UUID M1 verificado; omitir si falta. `MUNICIPAL_TAX`: requerido antes de preparar salida M8. No publicar `null` bajo este borrador. |

No se agregan campos distintos por consumidor: Core debe distribuir un mismo hecho a quienes corresponda. M4/M7 requieren filtrar por `originType`; la recepción de `debtSettled` por M8 para obligaciones M4/M7 sigue pendiente de acuerdo. La idempotencia del outbox es por imputación/deuda (`PaymentAllocation` + `Debt`) o por deuda en una cancelación sin imputación; al reintentar, se conserva `eventId` y el snapshot monetario.

| `originType` | `originId` conservado exactamente | `infractionId` |
|---|---|---|
| `PERMIT_FEE` | `permitFeeGenerated.data.id` de M4 | Omitido |
| `COMMERCIAL_FINE` | `commercialFineGenerated.data.id` de M4 | Omitido |
| `TRAFFIC_FINE` | `infractionConfirmed.data.infractionId` de M7 | Igual a `originId` |
| `MUNICIPAL_TAX` (sólo `debtSettled`, propuesta bloqueada) | `liquidation.integration_id` UUID persistente de M5 | Omitido |

No se sintetiza `citizenId` desde DNI, CUIT o PK `Long`. La deuda municipal `MUNICIPAL_TAX` no se encola como `debtSettled` hasta cerrar el contrato compartido, obtener identidad M1 verificable y confirmar la recepción M8. Para M4/M7 se reutiliza sólo la correlación verificable del evento de origen; si falta, el código actual conserva un snapshot económico aplazado. Snapshots y outbox históricos con `payment-<Long>`/`debt-<Long>` requieren revisión y no se publican silenciosamente bajo este borrador.

### `citizenId`: forma única propuesta y compatibilidad por receptor

| Forma en `debtSettled.data` | M4 | M7 | M8 |
|---|---|---|---|
| **Omitido** | Es la forma del documento de trabajo M4; compatible en estructura, sujeto a aceptación del UUID de deuda. | V4 lo describe opcional; compatible en estructura si el receptor no lo exige por negocio. | El PDF previo ejemplifica `citizenId` presente; **no hay aceptación documentada** de omisión. Para `MUNICIPAL_TAX` M5 no propone omitirlo. |
| **`null` explícito** | Campo adicional no documentado: **no asumir** aceptación. | V4 admite `string|null` opcional; parece sintácticamente admisible, pero requiere prueba bilateral. | Una propuesta M5→M8 anterior lo admitía para organización; el PDF M8 no confirma ese caso. **No usar** sin aceptación M8. |
| **UUID canónico M1** | Campo adicional respecto del documento M4; confirmar si lo ignora/usa. | Cabe en `string` V4; verificar que sea el mismo ciudadano M1 y que no se interprete como ID legado. | Es el objetivo de la propuesta UUID M5→M8. Requisito de esta variante municipal; M8 debe ratificarlo. |

La serialización local actual de los DTOs con `@JsonInclude(NON_NULL)` **omite** `citizenId` cuando no existe identidad canónica; no emite `null`. El schema propuesto valida omisión o UUID, y obliga a UUID para `MUNICIPAL_TAX`. Cualquier cambio a `null` o a un campo obligatorio para todos sería una revisión del contrato, no una compatibilidad presumida.

### Correlación y causalidad de M4

El envelope Core admite `correlationId` UUID **o `null`**; no inferimos que Core exija siempre un UUID. Si el `permitFeeGenerated`/`commercialFineGenerated` original trae UUID válido, M5 lo preserva en las respuestas. Si trae `null`, M5 **no** lo genera desde `eventId`, PK ni `originId`. Hoy `economicCorrelation` devuelve ausencia y `paymentRegistered`/`debtSettled`/`paymentReversed` quedan en un snapshot económico diferido, aun cuando la operación monetaria local ya ocurrió. Debe acordarse con Core/M4 si una respuesta derivada con `correlationId:null` se acepta: si sí, una adaptación futura podrá liberar esos snapshots sin recalcular montos ni crear otro hecho; si no, se requiere correlación autoritativa de la fuente y revisión manual. El `sourceEventId` y el origen estable quedan guardados para esta conciliación. `causationId` local sigue siendo `null`; cualquier uso del `eventId` causal requiere acuerdo y cambio separado, nunca un identificador fabricado.

### Orden, idempotencia e importes

La entrega puede reintentarse o llegar fuera de orden: `paymentReversed` o `debtSettled` pueden verse antes de `paymentRegistered` en un receptor. No se exige orden global de Core. El consumidor debe deduplicar por `eventId`, identificar la entidad por `paymentId`/`debtId`/`originType`/`originId`, retener temporalmente dependencias ausentes y conciliar con consistencia eventual. `debtSettled` no sustituye al registro de cada pago; una reversión posterior puede reabrir saldo y no debe borrarse el historial de la cancelación. Si recibe el mismo `eventId` otra vez, debe hacer ACK sin repetir el efecto. `remainingBalance` es un **snapshot del hecho**, no una orden de sobrescribir el saldo actual por orden de llegada; los deltas de imputación/reversión se aplican una vez y se reconcilian contra la obligación fuente. No existe aún una secuencia/versionado monotónico de deuda en el payload, por lo que un receptor que no pueda reconstruir saldos desde su ledger necesita acordar una consulta o proceso de conciliación, no confiar en `occurredAt` como orden total. Los criterios de retención, retry y DLQ los acuerda cada receptor con Core.

Los importes viajan como `number` decimal de moneda con escala de centavos (en Java `BigDecimal`, nunca `float` para cálculos). Para un pago distribuido entre varias deudas, se emite **un `paymentRegistered` por porción imputada**: mismo `paymentId`, diferentes `debtId` y `eventId`, `amountApplied` igual a esa porción, `remainingBalance` igual al saldo de **esa deuda luego de esa porción**. La suma de porciones no debe superar el pago confirmado; fondos no imputados no generan evento de deuda. `debtSettled` sale sólo cuando el saldo de esa deuda llega a 0. Una reversión ejecutada emite un `paymentReversed` por porción original, mismo `paymentId`/`debtId` y `reversedAmount` de esa porción; `remainingBalance` es el saldo **posterior a esa reversión**, que puede ser distinto del saldo original si hubo otros movimientos. El snapshot diferido no recalcula estos valores. Las pruebas locales cubren distribución M4+M7, cuota distribuida y reversión por porción.

## Ejemplo ilustrativo: `paymentRegistered` de M4

```json
{
  "eventId": "4c712abe-b51f-4e09-9d32-b2ff31ce97b4",
  "eventType": "paymentRegistered",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-26T10:00:00-03:00",
  "sourceModule": "rentas",
  "correlationId": "ca16af37-771a-4912-8e6a-1919c341a113",
  "causationId": null,
  "data": {
    "paymentId": "78168ad3-372b-49bc-a08e-fbf69a2bfd5a",
    "debtId": "02c9affc-dcef-4d28-807b-fb530fd5f5f4",
    "originType": "PERMIT_FEE",
    "originId": "FEE-001",
    "amountApplied": 50000.00,
    "paidAt": "2026-10-26T10:00:00-03:00",
    "remainingBalance": 25000.00
  }
}
```

Los UUID del ejemplo son ficticios. En operación, `paymentId` proviene de `payment.integration_id` (V27) y `debtId` de `debt.integration_id` (V25); las PK internas siguen siendo `BIGINT`. V27 se agregó al código pero **no se ejecutó en la base `rentas`** durante esta preparación.

## Ejemplos adicionales: cancelación y reversión

`debtSettled` para una infracción M7 con identidad M1 verificada. `remainingBalance` y `outstandingBalance` son cero. Los UUID son ilustrativos; `originId` e `infractionId` son el mismo UUID entregado por M7.

```json
{
  "eventId": "d2c1e472-5a7d-4c80-a310-387c643596b5",
  "eventType": "debtSettled",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-26T11:00:00-03:00",
  "sourceModule": "rentas",
  "correlationId": "ca16af37-771a-4912-8e6a-1919c341a113",
  "causationId": null,
  "data": {
    "debtId": "91d91a42-3b08-4645-a89d-b65220eb69b4",
    "citizenId": "0d322adc-243e-43da-843a-4b9c63b78d57",
    "conceptCode": "TRAFFIC_INFRACTION",
    "originType": "TRAFFIC_FINE",
    "originId": "b63caa31-b8e1-4981-8348-ce40c0cb4490",
    "infractionId": "b63caa31-b8e1-4981-8348-ce40c0cb4490",
    "settledAt": "2026-10-26T11:00:00-03:00",
    "remainingBalance": 0.00,
    "outstandingBalance": 0.00
  }
}
```

`paymentReversed` para una tasa M4. La reversión es otro hecho y tiene `eventId` propio, pero conserva `paymentId`, `debtId` y `originId`; `infractionId` no aparece.

```json
{
  "eventId": "47cc4c97-cfb0-49cf-8a9f-d2bc4f56bb9d",
  "eventType": "paymentReversed",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-26T12:00:00-03:00",
  "sourceModule": "rentas",
  "correlationId": "ca16af37-771a-4912-8e6a-1919c341a113",
  "causationId": null,
  "data": {
    "paymentId": "78168ad3-372b-49bc-a08e-fbf69a2bfd5a",
    "debtId": "02c9affc-dcef-4d28-807b-fb530fd5f5f4",
    "originType": "PERMIT_FEE",
    "originId": "FEE-001",
    "reversedAmount": 50000.00,
    "reversedAt": "2026-10-26T12:00:00-03:00",
    "remainingBalance": 75000.00,
    "reason": "Anulación de comprobante"
  }
}
```

## Variante propuesta `MUNICIPAL_TAX` para M8 — no publicable aún

Se usa el mismo `debtSettled`, sin otro `eventType` ni publisher por destinatario. `debtId` es `debt.integration_id` persistente; `originId` es el UUID textual de `liquidation.integration_id` persistente; `citizenId` sólo puede ser el UUID canónico M1 verificado. `conceptCode` conserva el código real del concepto, `infractionId` se omite y ambos saldos son cero. El backend sólo construye hoy un candidato local de este payload y registra aplazamiento; **no lo encola ni lo publica**. La identidad M1, la aceptación de M8 del contrato único, el filtrado por `originType`, la versión y la política de correlación/causalidad siguen bloqueando la salida real.

```json
{
  "eventId": "2a4fd82a-6be0-4b14-bb18-c25df8b60478",
  "eventType": "debtSettled",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-26T13:00:00-03:00",
  "sourceModule": "rentas",
  "correlationId": null,
  "causationId": null,
  "data": {
    "debtId": "653d9e79-2cd1-4ec1-8ada-845374f6903d",
    "citizenId": "2808fa6c-1e50-4f71-a46e-ca444458924b",
    "conceptCode": "TASA_SERVICIOS",
    "originType": "MUNICIPAL_TAX",
    "originId": "d53f7338-269c-428f-89a5-03eda40a9be7",
    "settledAt": "2026-10-26T13:00:00-03:00",
    "remainingBalance": 0.00,
    "outstandingBalance": 0.00
  }
}
```

`correlationId:null` ilustra la nulabilidad del envelope, **no** una decisión de activar esta variante con correlación nula. Los JSON de ejemplo y el schema propuesto en `../schemas/m5-economic-outbound.proposed.schema.json` son material de revisión, no evidencia de emisión real. Los ocho ejemplos individuales se guardan en `examples/m5-economic/` y se validan con `../tests/Validate-M5EconomicProposal.ps1`. El schema valida forma, tipos, UUID, dos decimales y condiciones por `originType`; la igualdad `originId=infractionId`, los saldos entre movimientos, el monto total de un pago distribuido y la legitimidad de identidades externas requieren validación de negocio/ledger y pruebas adicionales, no se deducen de un JSON aislado.

## Pendientes de aceptación

- M4/M7/M8: confirmar recepción del cambio de `paymentId`/`debtId` desde prefijos numéricos a UUID, presencia/ausencia de `citizenId`, filtrado por `originType`, manejo fuera de orden y semántica de `debtSettled` compartido.
- Core: definir `eventVersion` y transición antes de activar transporte; confirmar la aceptación de correlación M4 `null` en respuestas derivadas, sin exigir un UUID no documentado.
- M7: confirmar catálogo `conceptCode` (`TRAFFIC_INFRACTION` frente al ejemplo `TRAFFIC_FINE`).
- M4: confirmar catálogo `conceptCode` para tasas y multas; los códigos de ejemplos son ilustrativos.
- M1: proporcionar vínculo canónico de ciudadano para informar `citizenId` sin inventarlo; M8 debe aceptar la variante municipal y definir tratamiento de personas jurídicas antes de publicarla.
