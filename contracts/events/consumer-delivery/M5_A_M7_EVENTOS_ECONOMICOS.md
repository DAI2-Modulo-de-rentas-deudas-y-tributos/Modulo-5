# Entrega propuesta M5 → M7 — eventos económicos

**Estado:** propuesta de M5, no distribuida ni aceptada por Tránsito. **Corte:** 26/10/2026. Perfil de lectura para M7; el detalle compartido y el schema canónico están en [M5_ECONOMIC_OUTBOUND_UUID_DRAFT.md](../M5_ECONOMIC_OUTBOUND_UUID_DRAFT.md), [propuesta de adaptación](../M5_ECONOMIC_ADAPTATION_M4_M7_M8_PROPOSAL.md) y [schema propuesto](../../schemas/m5-economic-outbound.proposed.schema.json).

## Contrato y versión

M7 recibiría el mismo contrato compartido M5 para `paymentRegistered`, `paymentReversed` y `debtSettled`, filtrando `originType=TRAFFIC_FINE`. No se crea una versión ni payload M7 separado. Envelope Core de ocho campos requerido: `eventId`, `eventType`, `eventVersion`, `occurredAt`, `sourceModule`, `correlationId`, `causationId`, `data`; `correlationId` y `causationId` son UUID o `null`; `eventId` UUIDv4 estable para reintento; `sourceModule=rentas`.

El valor local ilustrado de `eventVersion` se mantiene en `1.0`; el valor/estrategia para interoperar con UUID M5 debe confirmarse con Core y M7 antes de activar. No se propone incrementar ni reinterpretar su valor unilateralmente.

## Payloads M5 propuestos para M7

| Evento/campo | Tipo JSON | Required / nullable | Regla y fuente |
|---|---|---|---|
| `paymentId` | string UUID | Sí / no nullable | UUID persistente M5 de Payment; cambia semántica frente a formatos prefijados, aunque M7 V4 tipa como string. |
| `debtId` | string UUID | Sí / no nullable | UUID persistente M5 de Debt. |
| `originType` | string enum | Sí / no nullable | `TRAFFIC_FINE`. |
| `originId`, `infractionId` | string UUID | Ambos sí / no nullable | Ambos iguales al `infractionId` original recibido de M7; no sustituir por UUID M5. |
| `amountApplied` | number decimal positivo | Sólo paymentRegistered; sí/no nullable | Imputación a esta deuda, no total de caja. |
| `paidAt` | string date-time con offset | Sólo paymentRegistered; sí/no nullable | Instante efectivo del pago. |
| `reversedAmount` | number decimal positivo | Sólo paymentReversed; sí/no nullable | Porción efectivamente revertida. |
| `reversedAt`, `reason` | string date-time con offset; string no vacío | Sólo paymentReversed; ambos sí/no nullable | Fecha de ejecución y motivo funcional. |
| `remainingBalance` | number decimal ≥ 0 | Sí en los tres; no nullable | Snapshot posterior al hecho; cero para deuda saldada. |
| `debtSettled.debtId`, `conceptCode`, `settledAt`, `outstandingBalance` | UUID; string no vacío; date-time; decimal | Sí/no nullable | `remainingBalance=0` y `outstandingBalance=0`. |
| `citizenId` | string UUID | Opcional y omitido si no hay UUID M1 verificado; si aparece, no nullable | M7 V4 describe el campo como opcional `string|null`; M5 propone sólo UUID canónico o ausencia, nunca DNI/CUIT/PK ni `null` sin acuerdo. |

Todos los campos comunes de identidad, monto y fecha de cada variante son obligatorios y no nulos. `infractionId` es obligatorio sólo con `TRAFFIC_FINE`; para M4 se omite y no se debe agregar a otros orígenes. Las claves opcionales se omiten, no se envían como `null`.

## UUID M5 frente a IDs M7 y ejemplos completos

`paymentId` y `debtId` pertenecen a M5 y son UUID persistentes; las PK internas siguen Long. `originId` e `infractionId` pertenecen a M7 y se conservan iguales al UUID original de `infractionConfirmed`. `citizenId` sólo puede ser identidad canónica M1 verificada. M7 debe conservar su correlación fuente; si no hay correlación verificable, M5 difiere el evento, sin reconstruirla.

Ejemplos completos reutilizados de los fixtures existentes:

`paymentRegistered`:

```json
{
  "eventId": "68c353a5-1f9d-4096-a7f4-d468b60e9b88",
  "eventType": "paymentRegistered",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-26T11:00:00-03:00",
  "sourceModule": "rentas",
  "correlationId": "ca16af37-771a-4912-8e6a-1919c341a113",
  "causationId": null,
  "data": {
    "paymentId": "633ee23f-3e91-4fd0-91e8-81fcb163e91d",
    "debtId": "91d91a42-3b08-4645-a89d-b65220eb69b4",
    "originType": "TRAFFIC_FINE",
    "originId": "b63caa31-b8e1-4981-8348-ce40c0cb4490",
    "infractionId": "b63caa31-b8e1-4981-8348-ce40c0cb4490",
    "amountApplied": 100000.00,
    "paidAt": "2026-10-26T11:00:00-03:00",
    "remainingBalance": 0.00
  }
}
```

`debtSettled`:

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

Fuentes: [paymentRegistered M7](../examples/m5-economic/m7-payment-registered.proposed.json) y [debtSettled M7](../examples/m5-economic/m7-debt-settled.proposed.json). También está el [pago M7 distribuido](../examples/m5-economic/distributed-m7-payment-registered.proposed.json). No existe fixture de reversión M7; se adapta el mismo contrato, con `originType=TRAFFIC_FINE` e `originId=infractionId` original:

```json
{
  "eventId": "b1f8f9af-5eb7-4b93-8c47-5896f94db972",
  "eventType": "paymentReversed",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-26T15:00:00-03:00",
  "sourceModule": "rentas",
  "correlationId": "ca16af37-771a-4912-8e6a-1919c341a113",
  "causationId": null,
  "data": {
    "paymentId": "633ee23f-3e91-4fd0-91e8-81fcb163e91d",
    "debtId": "91d91a42-3b08-4645-a89d-b65220eb69b4",
    "originType": "TRAFFIC_FINE",
    "originId": "b63caa31-b8e1-4981-8348-ce40c0cb4490",
    "infractionId": "b63caa31-b8e1-4981-8348-ce40c0cb4490",
    "reversedAmount": 100000.00,
    "reversedAt": "2026-10-26T15:00:00-03:00",
    "remainingBalance": 100000.00,
    "reason": "Anulación autorizada"
  }
}
```

## Diferencias con M7 Core V4

La referencia del handoff es **M7 Tránsito Core V4, 19/10/2026**: `paymentId`/`debtId` son JSON `string`, y la identidad de origen de multa es `infractionId` UUID; V4 no obliga a que los IDs M5 sean UUID, por lo que la forma conserva tipo pero cambia semántica/formato frente a `payment-<Long>`/`debt-<Long>`. M5 propone UUID persistentes propios y conserva `originId=infractionId` de M7. No hay evidencia aquí de que el parser, tablas, claves de deduplicación o consumidores de M7 ya acepten ese cambio.

Quedan por contrastar con M7 el catálogo `conceptCode` (`TRAFFIC_INFRACTION` local frente a `TRAFFIC_FINE` ilustrativo), la ausencia de `citizenId` frente a `string|null` opcional, la estabilidad de UUIDs M5, y respuesta con correlación. No cambiar la entrada `infractionConfirmed` de M7.

## Correlación, idempotencia y orden

Preservar `correlationId` auténtico de `infractionConfirmed`; no derivar correlación de ID de entidad. `eventId` es idempotency key de entrega; el receptor debe ACK de duplicado sin repetir efecto. La clave económica adicional debe distinguir `paymentId + debtId + eventType` y la porción/evento si hay múltiples imputaciones. No asumir orden: una reversión puede preceder al pago, o `debtSettled` anteceder a una reversión posterior. Retener hechos no resolubles, conciliar contra ledger y no sobrescribir saldo actual con un snapshot viejo. Core/M7 deben cerrar retries, timeout, DLQ y ACK.

## Confirmaciones solicitadas a M7

1. ¿Acepta UUID textual persistente de M5 para `paymentId`/`debtId` manteniendo M7 V4 como string, y cómo migra datos/índices que esperan prefijos?
2. ¿Confirma que `originId` e `infractionId` deben ser idénticos al `infractionId` M7 original en los tres eventos?
3. ¿Qué valor exacto de `conceptCode` espera para multas de tránsito?
4. ¿Acepta omisión de `citizenId` sin identidad canónica M1 y qué hace con `null` recibido históricamente?
5. ¿Qué reglas aplica a correlación causal, deduplicación, eventos fuera de orden y cancelación/reversión?
6. ¿Qué `eventVersion`/corte compatible acuerda con Core y cuál sería la prueba conjunta de regresión de `infractionConfirmed` más pagos/reversiones?

## Aceptación y prueba conjunta propuestas

Probar pago parcial y total, settlement, reversión ejecutada y reversión entregada antes del registro; misma infracción con UUID original y correlación conservados; duplicado `eventId`, reintento tras reinicio, ausencia de deuda/pago duplicado y filtrado de orígenes M4/M8. M7 confirma recepción y resultado del consumidor. Esta propuesta no habilita Core/RabbitMQ.
