# Entrega propuesta M5 → M4 — eventos económicos

**Estado:** propuesta técnica de M5, no distribuida ni aceptada por Habilitaciones. **Corte:** 26/10/2026. Este perfil resume sólo lo que M4 debe revisar; la especificación transversal y el schema canónico siguen en [M5_ECONOMIC_OUTBOUND_UUID_DRAFT.md](../M5_ECONOMIC_OUTBOUND_UUID_DRAFT.md), [M5_ECONOMIC_ADAPTATION_M4_M7_M8_PROPOSAL.md](../M5_ECONOMIC_ADAPTATION_M4_M7_M8_PROPOSAL.md) y [schema propuesto](../../schemas/m5-economic-outbound.proposed.schema.json).

No cambia contratos entrantes de M4, nombres de eventos ni envelope Core. Los JSON completos de abajo reutilizan fixtures existentes de M5 y son ilustrativos, no mensajes capturados ni evidencia de aceptación.

## Contrato y versión

Un único contrato M5 compartido. M4 recibiría `paymentRegistered`, `paymentReversed` y `debtSettled` para `originType=PERMIT_FEE` o `COMMERCIAL_FINE`. Los tres conservan el envelope Core de ocho campos: `eventId`, `eventType`, `eventVersion`, `occurredAt`, `sourceModule`, `correlationId`, `causationId`, `data`. Todos son requeridos; `correlationId` y `causationId` admiten UUID o `null`. `eventId` es UUIDv4, estable al reintentar el mismo evento; `sourceModule` es `rentas`.

`eventVersion` permanece en el valor local actual `1.0` en los ejemplos; no se incrementa ni declara aceptado. Core y M4 deben confirmar si ese valor sirve para la transición de IDs antes de activar el transporte.

## Payloads M5 propuestos para M4

| Evento/campo | Tipo JSON | Required / nullable | Regla y fuente |
|---|---|---|---|
| `paymentRegistered.data.paymentId` | string UUID | Sí / no nullable | UUID persistente de `Payment` M5; no es la PK Long. |
| `paymentRegistered.data.debtId` | string UUID | Sí / no nullable | UUID persistente de `Debt` M5. |
| `originType` | string enum | Sí / no nullable | Sólo `PERMIT_FEE` o `COMMERCIAL_FINE` en el perfil M4. |
| `originId` | string no vacío | Sí / no nullable | Copia exacta del `data.id` original de M4 (`permitFeeGenerated` / `commercialFineGenerated`); no sustituir por `externalRef` ni UUID M5. |
| `amountApplied` | number decimal positivo | Sí / no nullable | Imputación a esta deuda, no total de caja. |
| `paidAt` | string date-time con offset | Sí / no nullable | Hora efectiva del pago. |
| `remainingBalance` | number decimal ≥ 0 | Sí / no nullable | Snapshot después de esta imputación. |
| `paymentReversed.data.paymentId`, `debtId`, `originType`, `originId` | Igual a los campos equivalentes de pago | Sí / no nullable | Referencian el pago/deuda/origen originales. |
| `reversedAmount` | number decimal positivo | Sí / no nullable | Porción de la imputación efectivamente revertida. |
| `reversedAt` | string date-time con offset | Sí / no nullable | Hora efectiva de ejecución de reversión. |
| `reason` | string no vacío | Sí / no nullable | Motivo funcional de reversión. |
| `debtSettled.data.debtId` | string UUID | Sí / no nullable | UUID persistente M5 de la deuda. |
| `citizenId` | string UUID | Opcional; si aparece, no nullable | Sólo UUID canónico M1 verificado; si no existe, se omite, nunca se sintetiza desde CUIT/PK. M4 debe confirmar si acepta este campo adicional. |
| `conceptCode` | string no vacío | Sí / no nullable | Código M5; catálogo para M4 pendiente de confirmación. |
| `settledAt` | string date-time con offset | Sí / no nullable | Instante de saldo cero. |
| `remainingBalance`, `outstandingBalance` | number decimal | Sí / no nullable | Ambos exactamente `0` para este evento. |
| `infractionId` | string UUID | No aplica; debe omitirse | Campo sólo para `TRAFFIC_FINE`; no agregarlo a eventos M4. |

En el envelope `correlationId` puede ser `null` conforme a la forma Core, pero **M5 no fabricará correlación**. Si el evento fuente M4 no permite recuperar una correlación UUID verificable, la salida queda diferida hasta decisión de M4/Core. Los campos de `data` descritos como opcionales se omiten cuando no están disponibles; no se envían como `null`.

Cada mensaje representa una imputación a una deuda. Un pago repartido genera un `paymentRegistered` por porción; `debtSettled` sólo se produce al llegar esa deuda a saldo cero; `paymentReversed` sólo después de ejecutar la reversión y por la porción original.

## Identidades y ejemplos completos

- `paymentId`/`debtId`: UUID de entidades M5 persistidos; el Long interno no se publica.
- `originId`: ID original M4, preservado carácter por carácter (`FEE-...`/`FINE-...`).
- No hay `infractionId` en el perfil M4.
- `citizenId` no reemplaza CUIT ni se deriva de él.

Ejemplos completos reutilizados de los fixtures existentes:

`paymentRegistered`:

```json
{
  "eventId": "84ac0249-5f08-43c6-9c85-f0eea5c89324",
  "eventType": "paymentRegistered",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-26T14:00:00-03:00",
  "sourceModule": "rentas",
  "correlationId": "14f70bf7-cd42-4278-88a9-67ef9414487d",
  "causationId": null,
  "data": {
    "paymentId": "abf69d71-c0f4-4904-869c-e7eb78a63b99",
    "debtId": "74bc8a8d-6d1c-44ac-a77c-86fb46e931fd",
    "originType": "COMMERCIAL_FINE",
    "originId": "FINE-DISTRIBUTED",
    "amountApplied": 40.00,
    "paidAt": "2026-10-26T14:00:00-03:00",
    "remainingBalance": 60.00
  }
}
```

`debtSettled` sin `citizenId`:

```json
{
  "eventId": "2d8880e8-2403-4559-8392-73b4bbb40ef8",
  "eventType": "debtSettled",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-26T10:30:00-03:00",
  "sourceModule": "rentas",
  "correlationId": "57eed108-717d-4b3c-ae4a-0c8804ef2446",
  "causationId": null,
  "data": {
    "debtId": "1eb43cff-1c1a-4791-ae75-bb58ab14a901",
    "conceptCode": "MULTA_COMERCIAL",
    "originType": "COMMERCIAL_FINE",
    "originId": "FINE-001",
    "settledAt": "2026-10-26T10:30:00-03:00",
    "remainingBalance": 0.00,
    "outstandingBalance": 0.00
  }
}
```

`paymentReversed`:

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

Fuentes: [paymentRegistered distribuido](../examples/m5-economic/distributed-m4-payment-registered.proposed.json), [debtSettled sin citizenId](../examples/m5-economic/m4-debt-settled-no-citizen.proposed.json), [paymentReversed](../examples/m5-economic/m4-payment-reversed.proposed.json). También existe [paymentRegistered con correlationId nulo](../examples/m5-economic/m4-payment-registered-null-correlation.proposed.json); es forma sintáctica, no aprobación de nulo para respuesta derivada.

## Diferencias con la referencia M4 y compatibilidad

La fuente disponible es el **Contrato M4 Octubre 2026, versión de trabajo**, no una ratificación de su consumidor económico. Los contratos entrantes de M4 conservan sus IDs `data.id`; esta propuesta cambia la representación semántica de IDs **propiedad M5** desde prefijos/Long hacia UUID, aunque sigan siendo JSON `string`. No se encontró evidencia de que M4 ya acepte UUID de `paymentId`/`debtId`, el campo opcional `citizenId`, catálogo `conceptCode`, omisión de campos condicionales, o `correlationId:null` en respuestas.

No se afirma compatibilidad de parseo, persistencia, índices, deduplicación ni orden en el receptor M4. No versionar unilateralmente el evento para resolver estas diferencias.

## Orden, idempotencia y fuera de orden

No se promete orden global ni exactamente una entrega. M4 debe deduplicar por `eventId`, ACK de duplicados sin repetir el efecto y retener/reconciliar dependencias faltantes. Una reversión puede llegar antes del registro original; debe quedar pendiente y aplicarse una sola vez cuando haya evidencia del pago. `remainingBalance` es snapshot del hecho, no instrucción para sobrescribir el saldo actual según llegada. Los importes son `number` decimales de dos cifras (sin float para cálculo). Retry, timeout, DLQ y regla de ACK necesitan acuerdo con Core/M4.

## Confirmaciones solicitadas a M4

1. ¿Acepta `paymentId` y `debtId` como UUID persistentes de M5, sin cambiar los IDs originales M4 de `originId`?
2. ¿Acepta `citizenId` opcional omitido o UUID canónico M1, y `correlationId:null` si el evento M4 originario venía sin correlación?
3. ¿Cuáles son los códigos exactos `conceptCode` para tasas y multas, y qué valores `originType` procesa?
4. ¿Cómo filtra los eventos compartidos y cómo ACK/ignora eventos fuera de su origen?
5. ¿Qué estrategia de versión/corte requiere si ya persistió `payment-<Long>`/`debt-<Long>`?
6. ¿Cómo retiene eventos fuera de orden y qué política de idempotencia, retry/DLQ y prueba conjunta acuerda?

## Aceptación y prueba conjunta propuestas

M4 debe confirmar por escrito la versión/compatibilidad y ejecutar contra su adaptador: pago parcial, pago dividido, liquidación exacta, reversión ejecutada y reversión antes del pago; verificar `originId` original, importes/saldos, duplicado de `eventId`, correlación, reinicio/reintento y filtrado. Sólo luego de la preparación receptora y aprobación Core se puede considerar activar la entrega real.
