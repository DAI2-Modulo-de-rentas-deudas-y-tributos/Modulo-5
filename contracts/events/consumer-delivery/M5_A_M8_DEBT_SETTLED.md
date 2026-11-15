# Entrega propuesta M5 → M8 — restricciones de debtSettled

**Estado:** variante municipal expresamente bloqueada; documento de coordinación, no distribuido ni aceptado por Desarrollo Social. **Corte:** 26/10/2026. No crea otro evento. Ver contrato común en [borrador saliente M5](../M5_ECONOMIC_OUTBOUND_UUID_DRAFT.md), [adaptación M4/M7/M8](../M5_ECONOMIC_ADAPTATION_M4_M7_M8_PROPOSAL.md) y [schema propuesto](../../schemas/m5-economic-outbound.proposed.schema.json).

## Alcance, contrato y versión

M8 es consumidor previsto del mismo `debtSettled` que comparten M4/M7/M8. Este perfil sólo describe la propuesta `originType=MUNICIPAL_TAX`. **M5 mantiene esa publicación bloqueada** hasta cerrar contrato único, UUID canónico M1 verificable, filtrado y aceptación M8/Core. El PDF M8 existente no es ratificación de esta nueva variante.

El envelope mantiene exactamente ocho campos requeridos: `eventId`, `eventType`, `eventVersion`, `occurredAt`, `sourceModule`, `correlationId`, `causationId`, `data`. `correlationId`/`causationId` son UUID o `null`; `eventId` UUIDv4 y estable al reintentar; `sourceModule=rentas`. `eventVersion=1.0` aparece sólo como valor de ejemplo actual; se conserva sin cambio hasta confirmación externa. No se activa transporte real.

## Payload municipal propuesto (no publicable aún)

| Campo | Tipo JSON | Required / nullable | Regla propuesta |
|---|---|---|---|
| `debtId` | string UUID | Sí / no nullable | UUID persistente de la deuda M5; el mismo valor que M5 usa en `overdueDebt`. |
| `citizenId` | string UUID | Sí / no nullable para esta variante | UUID canónico M1 verificado; no DNI/CUIT, Long ni UUID inventado. Si es organización sin vínculo canónico, permanece diferido; no mandar `null`/omitir como atajo. |
| `conceptCode` | string no vacío | Sí / no nullable | Código de concepto tributario M5; catálogo y aceptación M8 pendientes. |
| `originType` | string enum | Sí / no nullable | `MUNICIPAL_TAX`; su publicación está bloqueada. |
| `originId` | string UUID | Sí / no nullable | UUID persistente M5 de la liquidación origen, no número/código de muestra anterior. |
| `settledAt` | string date-time con offset | Sí / no nullable | Instante real de cancelación. |
| `remainingBalance`, `outstandingBalance` | number decimal | Sí / no nullable | Ambos exactamente `0`. |
| `infractionId` | — | Debe omitirse | No corresponde a `MUNICIPAL_TAX`. |

No existe `paymentRegistered` ni `paymentReversed` de M5→M8 en este alcance. No se debe interpretar el `debtSettled` compartido como autorización para que M8 procese tasas/multas M4/M7.

## Ejemplo JSON completo reutilizado; expresamente bloqueado

El siguiente JSON copia el fixture existente [m8-municipal-debt-settled-blocked.proposed.json](../examples/m5-economic/m8-municipal-debt-settled-blocked.proposed.json). Es sintético y **no representa evento publicado ni aprobación M8**. Su `correlationId:null` tampoco constituye decisión de habilitarlo.

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

## Diferencias frente al original M8 y restricciones

El contrato original disponible es el PDF de Desarrollo Social, con ejemplos de identificadores prefijados como `debt-03200`, ciudadano `c-00123` y liquidación `liq-01001`. La propuesta sustituye únicamente identificadores propiedad M5 por UUID persistentes (`debtId`, `originId`) y exige `citizenId` UUID canónico M1. **No hay aceptación documentada de M8** para esos cambios, para el UUID de ciudadano, el formato `MUNICIPAL_TAX`, campos de saldo, omisión de otros orígenes ni versionado.

`MUNICIPAL_TAX` continúa bloqueado: no publicar si falta identidad M1, vínculo de liquidación, catálogo, autorización de filtrado o confirmación M8/Core. Mantener `eventVersion` intacta. No crear `debtSettledM8`, no alterar `debtSettled` entrante, no cambiar envelope, ni reescribir ejemplos originales como si estuvieran ratificados.

Si Core enruta por `eventType` y M8 recibe `debtSettled` con origen M4/M7, M8 debe definir su conducta de filtrar/ignorar/retener y ACK. Hasta confirmar routing y política, no asumir que M8 procesa ese origen.

## Identidades, correlación, idempotencia y fuera de orden

`debtId`/`originId` son UUID propios persistentes M5; `citizenId` pertenece a M1; no intercambiarlos. Preservar correlación verificable; no fabricar causalidad desde Long o timestamps. M8 debe deduplicar por `eventId`, ACK sin repetir efecto, tolerar consistencia eventual y no tratar llegada fuera de orden como orden económico. `remainingBalance=0` es snapshot del hecho, no permiso para sobrescribir una deuda actual ni para borrar un settlement previo si una reversión posterior reabre el saldo. Retry/DLQ/retención dependen del acuerdo M8/Core.

## Confirmaciones solicitadas a M8 y M1/Core

1. ¿M8 aceptará `debtId` UUID persistente M5 y `originId` UUID persistente de liquidación M5?
2. ¿Qué referencia de ciudadano recibe M8? ¿M1 garantiza UUID canónico para personas y organizaciones? ¿Qué hacer cuando no existe asociación?
3. ¿M8 ratifica `originType=MUNICIPAL_TAX`, `conceptCode`, saldo cero y los campos/fechas aquí definidos?
4. ¿M8 debe recibir `debtSettled` de M4/M7? ¿Cómo filtra/ACK de orígenes ajenos?
5. ¿Qué versión y transición acepta Core/M8? Mantener el valor actual hasta decisión conjunta.
6. ¿Qué política de correlación, idempotencia, eventos fuera de orden, retry/DLQ y prueba conjunta aplica?

## Criterio de aceptación conjunta

Antes de desbloquear: acuerdo M8+M1 de identidad y semántica; Core confirma routing/envelope/versionado; test conjunto con deuda municipal liquidada, duplicado, evento desordenado, persona sin UUID canónico (debe quedar diferida), filtrado de M4/M7 y una eventual reversión. Hasta entonces el resultado de M5 debe seguir siendo **DEFERRED/BLOCKED**, sin outbox publicable ni efecto M8.
