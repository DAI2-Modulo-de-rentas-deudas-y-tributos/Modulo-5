# Eventos salientes de M5 (Rentas)

**Versión de trabajo:** 10/10/2026. Pendiente de validación con los módulos consumidores.

Este documento incluye solo lo que publica M5; los eventos entrantes quedan documentados según cada productor.

## Mapa

| Destino | Eventos |
|---|---|
| M1 | exemptionRequested, updateExemptionStatus, paymentPlanRequested, updatePaymentPlanStatus |
| M2 | updateTicketStatus |
| M4 | paymentRegistered, debtSettled, paymentReversed |
| M7 | paymentRegistered, debtSettled, paymentReversed |
| M8 | overdueDebt, debtSettled, exemptionRequested, updateExemptionStatus |



## M1 · Ciudadanos

### exemptionRequested

Comunica que un contribuyente presentó una solicitud de exención. Este mismo mensaje puede recibirlo M8 si está suscripto.

```json
{
  "eventId": "9ab0074f-c63b-4177-85a8-60e26130a342",
  "eventType": "exemptionRequested",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-10T16:00:00-03:00",
  "sourceModule": "rentas",
  "correlationId": "53ffab11-34d9-49de-8a77-64d5f239d19e",
  "causationId": null,
  "data": {
    "requestId": "exr-00600",
    "citizenId": "c-00123",
    "conceptCode": "TASA_SERVICIOS",
    "reason": "Situación socioeconómica",
    "requestedPercentage": 100,
    "requestedFrom": "2026-09-01",
    "requestedUntil": "2027-08-31"
  }
}
```

### updateExemptionStatus

Informa la resolución de una solicitud de exención. Ejemplo aprobado.

```json
{
  "eventId": "fd8964d7-cb0d-4624-b494-90e780fce025",
  "eventType": "updateExemptionStatus",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-10T16:00:00-03:00",
  "sourceModule": "rentas",
  "correlationId": "53ffab11-34d9-49de-8a77-64d5f239d19e",
  "causationId": null,
  "data": {
    "requestId": "exr-00600",
    "citizenId": "c-00123",
    "status": "APPROVED",
    "exemptionId": "ex-00700",
    "conceptCode": "TASA_SERVICIOS",
    "percentage": 100,
    "validFrom": "2026-09-01",
    "validUntil": "2027-08-31"
  }
}
```

**ACLARACIÓN:** Si se rechaza, se conserva eventType y se utiliza data.status = REJECTED, data.reason con el motivo; no corresponde crear exemptionId ni porcentaje aprobado ficticios.

### paymentPlanRequested

Comunica una solicitud de plan de pago desde Rentas.

```json
{
  "eventId": "6b2ac085-80b1-4f59-ada2-8e98cc74733f",
  "eventType": "paymentPlanRequested",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-10T16:00:00-03:00",
  "sourceModule": "rentas",
  "correlationId": "d57bbe9a-05d8-4750-bf88-8e7b1850e4fa",
  "causationId": null,
  "data": {
    "requestId": "ppr-00800",
    "citizenId": "c-00123",
    "debtIds": [
      "debt-03001",
      "debt-03002"
    ],
    "totalDebt": 200000,
    "installments": 6
  }
}
```

### updatePaymentPlanStatus

Notifica el otorgamiento o rechazo de un plan de pago.

```json
{
  "eventId": "f14a59f8-d9cf-4ffc-b7ff-1e5e20b09e9d",
  "eventType": "updatePaymentPlanStatus",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-10T16:00:00-03:00",
  "sourceModule": "rentas",
  "correlationId": "d57bbe9a-05d8-4750-bf88-8e7b1850e4fa",
  "causationId": null,
  "data": {
    "requestId": "ppr-00800",
    "citizenId": "c-00123",
    "status": "GRANTED",
    "planId": "plan-00850",
    "installments": 6,
    "totalAmount": 220000
  }
}
```

**ACLARACIÓN:** Para un rechazo, data.status = REJECTED y data.reason explica la causa; planId y condiciones otorgadas se omiten.

**PENDIENTE:** Confirmar consumo real de los cuatro eventos, ID de ciudadano y eventual identificador de expediente.


## M2 · Atención Ciudadana

### updateTicketStatus · RESOLVED

Ejemplo completo de la variante de resolución de un ticket derivado a Rentas.

```json
{
  "eventId": "17f776a7-2e99-444a-a684-79a723c27607",
  "eventType": "updateTicketStatus",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-10T16:00:00-03:00",
  "sourceModule": "rentas",
  "correlationId": "013aa018-7a67-40ed-97fd-f13e8272f985",
  "causationId": null,
  "data": {
    "ticketId": "550e8400-e29b-41d4-a716-446655440000",
    "publicMessage": "El pago fue localizado e imputado correctamente.",
    "internalMessage": "Se verificó y corrigió la imputación.",
    "progress": 100,
    "details": {
      "resolution": {
        "type": "ACTION_COMPLETED"
      }
    },
    "attachments": [],
    "updatedBy": {
      "type": "EXTERNAL_USER",
      "id": "USR-M5-12"
    },
    "updateOccurredAt": "2026-10-10T16:00:00-03:00",
    "updateType": "RESOLVED"
  }
}
```

### Variantes del mismo evento

Los fragmentos siguientes son parciales de `data`; deben completarse con los campos obligatorios del contrato M2.

#### STARTED
```json
{
  "updateType": "STARTED",
  "publicMessage": "Rentas comenzó a gestionar el reclamo.",
  "details": null
}
```

#### PROGRESS
```json
{
  "updateType": "PROGRESS",
  "publicMessage": "Se está verificando la imputación del pago.",
  "progress": 50,
  "details": null
}
```

#### INFORMATION_REQUIRED
```json
{
  "updateType": "INFORMATION_REQUIRED",
  "publicMessage": "Necesitamos información adicional.",
  "details": {
    "informationRequest": {
      "messageForCitizen": "Adjunte el comprobante de pago completo.",
      "requiredBy": null
    }
  }
}
```

#### RETURNED
```json
{
  "updateType": "RETURNED",
  "internalMessage": "El reclamo corresponde a otra área.",
  "details": {
    "returnInfo": {
      "reasonCode": "REQUEST_TYPE_MISMATCH"
    }
  }
}
```

#### REJECTED
```json
{
  "updateType": "REJECTED",
  "publicMessage": "El pedido no reúne las condiciones necesarias.",
  "details": {
    "cancellation": {
      "reasonCode": "DOES_NOT_APPLY"
    }
  }
}
```

**PENDIENTE:** Validar con M2 que acepta envelope Core en reemplazo de specVersion/producer/subject exigidos por su v1.74.


## M4 · Habilitaciones

### paymentRegistered

Pago imputado a la tasa FEE-001. Una publicación por imputación; el saldo resultante es 0.

```json
{
  "eventId": "c2eab89c-a586-42e6-85b9-246c5e5744f0",
  "eventType": "paymentRegistered",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-10T16:00:00-03:00",
  "sourceModule": "rentas",
  "correlationId": "7ccb4926-15db-4d6c-a486-3d37a035f0bd",
  "causationId": null,
  "data": {
    "paymentId": "PAY-9001",
    "debtId": "DEBT-3001",
    "originType": "PERMIT_FEE",
    "originId": "FEE-001",
    "amountApplied": 50000,
    "paidAt": "2026-10-10T16:00:00-03:00",
    "remainingBalance": 0
  }
}
```

### debtSettled

La deuda asociada a la tasa FEE-001 queda totalmente cancelada. Este eventType también lo reciben M7 y M8 si están suscriptos.

```json
{
  "eventId": "11fc2aa9-0666-4b4a-af3c-f236494e5874",
  "eventType": "debtSettled",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-10T16:00:00-03:00",
  "sourceModule": "rentas",
  "correlationId": "7ccb4926-15db-4d6c-a486-3d37a035f0bd",
  "causationId": null,
  "data": {
    "debtId": "DEBT-3001",
    "citizenId": null,
    "conceptCode": "TASA_HABILITACION",
    "originType": "PERMIT_FEE",
    "originId": "FEE-001",
    "settledAt": "2026-10-10T16:00:00-03:00",
    "remainingBalance": 0,
    "outstandingBalance": 0
  }
}
```

### paymentReversed

Se revierte el pago antes confirmado, por lo que el saldo vuelve a 50.000. Es un ejemplo de evento independiente posterior.

```json
{
  "eventId": "ced3c01f-6f7d-496a-9f99-516f4f73c909",
  "eventType": "paymentReversed",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-10T16:00:00-03:00",
  "sourceModule": "rentas",
  "correlationId": "7ccb4926-15db-4d6c-a486-3d37a035f0bd",
  "causationId": null,
  "data": {
    "paymentId": "PAY-9001",
    "debtId": "DEBT-3001",
    "originType": "PERMIT_FEE",
    "originId": "FEE-001",
    "reversedAmount": 50000,
    "reversedAt": "2026-10-11T11:00:00-03:00",
    "remainingBalance": 50000,
    "reason": "Reversión autorizada"
  }
}
```

**ACLARACIÓN:** Para multas comerciales, `originType = COMMERCIAL_FINE`, `originId = FINE-001`; no incluir `infractionId`.
**PENDIENTE:** Confirmación del consumo, especialmente paymentReversed, y resolución del titular por establishmentId conforme a la arquitectura Core.


## M7 · Tránsito

### paymentRegistered

Se registró un pago completo aplicado a una infracción confirmada por M7.

```json
{
  "eventId": "59d65c37-4349-4ca9-97e4-2eeb26b73c08",
  "eventType": "paymentRegistered",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-10T16:00:00-03:00",
  "sourceModule": "rentas",
  "correlationId": "5a7f743e-0a91-4301-a422-e82198fce968",
  "causationId": null,
  "data": {
    "paymentId": "PAY-9200",
    "debtId": "DEBT-3100",
    "originType": "TRAFFIC_FINE",
    "originId": "93fd4382-2343-4f3f-a127-9328b29fc520",
    "infractionId": "93fd4382-2343-4f3f-a127-9328b29fc520",
    "amountApplied": 75000,
    "paidAt": "2026-10-10T16:00:00-03:00",
    "remainingBalance": 0
  }
}
```

### debtSettled

La deuda de la infracción quedó cancelada. M8 también recibirá el mensaje si se encuentra suscripto a debtSettled.

```json
{
  "eventId": "f42264c6-d810-49df-9bf7-eb0c154219ab",
  "eventType": "debtSettled",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-10T16:00:00-03:00",
  "sourceModule": "rentas",
  "correlationId": "5a7f743e-0a91-4301-a422-e82198fce968",
  "causationId": null,
  "data": {
    "debtId": "DEBT-3100",
    "citizenId": "c-00123",
    "conceptCode": "MULTA_TRANSITO",
    "originType": "TRAFFIC_FINE",
    "originId": "93fd4382-2343-4f3f-a127-9328b29fc520",
    "infractionId": "93fd4382-2343-4f3f-a127-9328b29fc520",
    "settledAt": "2026-10-10T16:00:00-03:00",
    "remainingBalance": 0,
    "outstandingBalance": 0
  }
}
```

### paymentReversed

Se anuló la imputación de 75.000; la deuda vuelve a quedar pendiente por ese monto.

```json
{
  "eventId": "2c308218-4848-427e-96df-fde02c4da435",
  "eventType": "paymentReversed",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-10T16:00:00-03:00",
  "sourceModule": "rentas",
  "correlationId": "5a7f743e-0a91-4301-a422-e82198fce968",
  "causationId": null,
  "data": {
    "paymentId": "PAY-9200",
    "debtId": "DEBT-3100",
    "originType": "TRAFFIC_FINE",
    "originId": "93fd4382-2343-4f3f-a127-9328b29fc520",
    "infractionId": "93fd4382-2343-4f3f-a127-9328b29fc520",
    "reversedAmount": 75000,
    "reversedAt": "2026-10-11T11:00:00-03:00",
    "remainingBalance": 75000,
    "reason": "Reversión autorizada"
  }
}
```

**PENDIENTE:** Confirmación de consumo por M7 y aceptación del payload unificado.


## M8 · Desarrollo Social

### overdueDebt

Comunica una deuda vencida relevante para Desarrollo Social. Conservamos el payload acordado y usamos el envelope Core.

```json
{
  "eventId": "b9cd9308-ba13-4703-b16c-767abf2e3108",
  "eventType": "overdueDebt",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-10T16:00:00-03:00",
  "sourceModule": "rentas",
  "correlationId": "66ac88b8-4384-4413-bb02-dbe2471eafad",
  "causationId": null,
  "data": {
    "debtId": "debt-03200",
    "citizenId": "c-00123",
    "conceptCode": "TASA_SERVICIOS",
    "outstandingAmount": 85000,
    "dueDate": "2026-09-20"
  }
}
```

### debtSettled

Ejemplo de cancelación de tributo generado por Rentas. Es el mismo contrato unificado que se entrega a M4 y M7 por suscripción, pero aquí originType = MUNICIPAL_TAX.

```json
{
  "eventId": "082ba3ff-be03-4713-add4-fd7880b19d4b",
  "eventType": "debtSettled",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-10T16:00:00-03:00",
  "sourceModule": "rentas",
  "correlationId": "66ac88b8-4384-4413-bb02-dbe2471eafad",
  "causationId": null,
  "data": {
    "debtId": "debt-03200",
    "citizenId": "c-00123",
    "conceptCode": "TASA_SERVICIOS",
    "originType": "MUNICIPAL_TAX",
    "originId": "liq-01001",
    "settledAt": "2026-10-10T16:00:00-03:00",
    "remainingBalance": 0,
    "outstandingBalance": 0
  }
}
```

### exemptionRequested

Solicitud de exención. La publicación puede ser la misma que reciban M1 y M8; no crear una copia por destinatario.

```json
{
  "eventId": "9ab0074f-c63b-4177-85a8-60e26130a342",
  "eventType": "exemptionRequested",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-10T16:00:00-03:00",
  "sourceModule": "rentas",
  "correlationId": "53ffab11-34d9-49de-8a77-64d5f239d19e",
  "causationId": null,
  "data": {
    "requestId": "exr-00600",
    "citizenId": "c-00123",
    "conceptCode": "TASA_SERVICIOS",
    "reason": "Situación socioeconómica",
    "requestedPercentage": 100,
    "requestedFrom": "2026-09-01",
    "requestedUntil": "2027-08-31"
  }
}
```

### updateExemptionStatus

Resolución de la solicitud de exención. Aquí se representa el caso aprobado.

```json
{
  "eventId": "fd8964d7-cb0d-4624-b494-90e780fce025",
  "eventType": "updateExemptionStatus",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-10T16:00:00-03:00",
  "sourceModule": "rentas",
  "correlationId": "53ffab11-34d9-49de-8a77-64d5f239d19e",
  "causationId": null,
  "data": {
    "requestId": "exr-00600",
    "citizenId": "c-00123",
    "status": "APPROVED",
    "exemptionId": "ex-00700",
    "conceptCode": "TASA_SERVICIOS",
    "percentage": 100,
    "validFrom": "2026-09-01",
    "validUntil": "2027-08-31"
  }
}
```

**PENDIENTE:** Acordar tratamiento de `citizenId` nulo, alcance de debtSettled y eventual reversión de deuda saldada.


## Reglas generales y pendientes

- Envelope de Core: `eventId`, `eventType`, `eventVersion`, `occurredAt`, `sourceModule`, `correlationId`, `causationId`, `data`; sin campos extra de primer nivel.
- Core enruta por `eventType`, así que los consumidores filtran por `originType`.
- `eventId` UUIDv4 único por publicación. No asumir orden. Idempotencia en consumidores.
- `occurredAt` se escribe así por el ejemplo de Core; el texto tiene también `occuredAt` (confirmar).
- `sourceModule: rentas` debe coincidir con el token de Core.
- Los payloads unificados son propuestas a validar con M4/M7/M8.
- Todos los demás detalles/puntos pendientes figuran en cada sección.
