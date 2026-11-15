# M5 · Rentas — Estandarización UUID de TODOS los eventos salientes

**Fecha:** 20/10/2026
**Estado:** propuesta de contrato **objetivo** de M5 para revisar/implementar con Codex. Algunos campos requieren aprobación de otros módulos antes del despliegue.
**Fuentes usadas:** M1 *Funciones del sistema y payloads*, M2 *Eventos v1.78*, M4 *Contrato de eventos – octubre 2026*, M7 *Payloads actualizados – Core V4* (19/10/2026), M8 *Eventos Desarrollo Social con Envelope*, contratos de M5 previos.
**Alcance:** SOLO eventos **publicados por M5**. No se reescribe ningún evento entrante de M1, M2, M4, M7 o M8.

## 1. Decisión técnica: el envelope de Core NO cambia

Se conserva exactamente esta estructura de PRIMER NIVEL, sin campos extra:

```json
{
  "eventId": "UUIDv4",
  "eventType": "camelCase",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-20T13:00:00-03:00",
  "sourceModule": "rentas",
  "correlationId": "UUID-o-null",
  "causationId": null,
  "data": {}
}
```

- `eventId`: UUIDv4 de **la ocurrencia de evento**; para reintento del mismo hecho se conserva el mismo `eventId`.
- `correlationId`: UUID de correlación del flujo (conservar el del evento causante o generar uno al iniciar el flujo).
- `causationId`: UUID del `eventId` causal cuando corresponda; de otro modo `null`.
- `sourceModule`: siempre `rentas` y coherente con el token.
- `eventType`, `eventVersion`, `occurredAt` y `data`: misma ubicación y semántica.
- **Solo se corrigen tipos/valores de identificadores dentro de `data`.** No se agrega `targetModule`, `producer`, `subject` ni `payload` al envelope.
- **IMPORTANTE SOBRE VERSIONADO:** la *estructura* del envelope no cambia. Si un schema desplegado exige antes otro formato de IDs, el nuevo contrato podría requerir coordinación de `eventVersion`/schema con Core y los receptores. No cambiar `eventVersion` unilateralmente.

## 2. Regla de identidad definitiva para el contrato objetivo

| Tipo | Formato | Responsable / fuente |
|---|---|---|
| `eventId` | UUIDv4 | M5, uno por hecho de negocio |
| `correlationId`, `causationId` | UUID o null | Evento/flujo |
| `citizenId` | UUID | **M1**, ID canónico de ciudadano; idéntico en M1/M2/M5/M8 y otros módulos |
| `ticketId` | UUID | **M2**, copiar el ID recibido, nunca crear otro |
| `updatedBy.id` | UUID o null | M5 identidad UUID **persistente** de la persona; SYSTEM/null únicamente acciones automáticas |
| `paymentId`, `debtId`, `requestId`, `planId`, `exemptionId` | UUID | M5, uno por entidad persistente |
| `debtIds[]` | array de UUID | M5, valores de deudas existentes |
| `originId` | UUID objetivo | UUID real de la entidad de origen: liquidación M5, multa M7, tasa/multa M4 (pendiente de ajuste de M4) |
| `infractionId` | UUID | M7, el recibido en `infractionConfirmed` |

**NO convertir a UUID:** `conceptCode`, `originType`, `status`, `reason`, `currency`, `debtorIdType`, `DNI`, `CUIL`, `CUIT`, `licensePlate`, `publicMessage`, fechas, importes, `installments`, `programName`. **No confundir IDs con documentos.**

**No inventar equivalencias:** para ciudadanos existentes, pedir/proyectar correspondencia oficial desde M1. Para entidades M4, `originId` debe coincidir exactamente con el ID real que M4 publicó y usa; no reemplazar `FEE-001` con UUID generado por Rentas.

## 3. Inventario completo: 9 nombres de evento, 5 módulos consumidores

| Destino | eventType | IDs UUID en `data` |
|---|---|---|
| M1 | `exemptionRequested` | `requestId`, `citizenId` |
| M1 | `updateExemptionStatus` | `requestId`, `citizenId`, `exemptionId` cuando existe |
| M1 | `paymentPlanRequested` | `requestId`, `citizenId`, cada `debtIds[]` |
| M1 | `updatePaymentPlanStatus` | `requestId`, `citizenId`, `planId` cuando existe |
| M2 | `updateTicketStatus` | `ticketId`, `updatedBy.id` si es persona |
| M4 | `paymentRegistered` | `paymentId`, `debtId`, `originId` (*) |
| M4 | `debtSettled` | `debtId`, `citizenId` si existe, `originId` (*) |
| M4 | `paymentReversed` | `paymentId`, `debtId`, `originId` (*) |
| M7 | `paymentRegistered` | `paymentId`, `debtId`, `originId`, `infractionId` |
| M7 | `debtSettled` | `debtId`, `citizenId` si existe, `originId`, `infractionId` |
| M7 | `paymentReversed` | `paymentId`, `debtId`, `originId`, `infractionId` |
| M8 | `overdueDebt` | `debtId`, `citizenId` |
| M8 | `debtSettled` | `debtId`, `citizenId` si existe, `originId` (*) |
| M8 | `exemptionRequested` | `requestId`, `citizenId` |
| M8 | `updateExemptionStatus` | `requestId`, `citizenId`, `exemptionId` cuando existe |

(*) `originId` se decide por `originType`. Para `MUNICIPAL_TAX`, UUID de liquidación de M5; para `TRAFFIC_FINE`, UUID de infracción M7; para `PERMIT_FEE` o `COMMERCIAL_FINE`, falta la confirmación del UUID real de M4. Como **Core rutea por eventType**, no se emite un evento diferente por cada consumidor. Los consumidores filtran por `originType` y demás reglas de negocio.

**Contratos compartidos intocables en la FORMA:**

- `paymentRegistered`: `paymentId`, `debtId`, `originType`, `originId`, `infractionId` **solo** cuando `TRAFFIC_FINE`, `amountApplied`, `paidAt`, `remainingBalance`.
- `debtSettled`: `debtId`, `citizenId` opcional/nulo según titular, `conceptCode`, `originType`, `originId`, `infractionId` **solo** cuando `TRAFFIC_FINE`, `settledAt`, `remainingBalance`, `outstandingBalance`.
- `paymentReversed`: `paymentId`, `debtId`, `originType`, `originId`, `infractionId` **solo** cuando `TRAFFIC_FINE`, `reversedAmount`, `reversedAt`, `remainingBalance`, `reason`.

No crear `debtSettledForM8`, `paymentRegisteredForM7` ni variantes similares. El mismo `requestId` de `exemptionRequested` se reutiliza en `updateExemptionStatus`, publicado una sola vez para M1/M8.

## 4. Ejemplos completos por evento y módulo

**Estos JSON muestran el contrato objetivo.** Los UUID de ejemplo se generan únicamente para documentación y son coherentes entre mensajes relacionados. **No copiar IDs de ejemplo a producción ni generar IDs nuevos al republicar entidades existentes.**

### M1 + M8 | exemptionRequested (una publicación, varios suscriptores)

```json
{
  "eventId": "cda4b5fc-b882-4d47-80b6-df010c6fe7b7",
  "eventType": "exemptionRequested",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-20T13:00:00-03:00",
  "sourceModule": "rentas",
  "correlationId": "381fe6da-6470-40ac-9cbb-77cd91066a87",
  "causationId": null,
  "data": {
    "requestId": "034c24df-c322-484f-9d23-489343563dba",
    "citizenId": "2575b9ce-65b8-449f-873b-62ac40328948",
    "conceptCode": "TASA_SERVICIOS",
    "reason": "Situación socioeconómica",
    "requestedPercentage": 100,
    "requestedFrom": "2026-11-01",
    "requestedUntil": "2027-08-31"
  }
}
```

**Observación:** Confirmar con M1 adopción del ciudadano UUID canónico; M8 tiene propuesta de corrección UUID.

### M1 + M8 | updateExemptionStatus APPROVED

```json
{
  "eventId": "7f3bbe52-efad-4eef-8889-a76793601f55",
  "eventType": "updateExemptionStatus",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-20T13:00:00-03:00",
  "sourceModule": "rentas",
  "correlationId": "381fe6da-6470-40ac-9cbb-77cd91066a87",
  "causationId": null,
  "data": {
    "requestId": "034c24df-c322-484f-9d23-489343563dba",
    "citizenId": "2575b9ce-65b8-449f-873b-62ac40328948",
    "status": "APPROVED",
    "exemptionId": "1fc6419c-834a-49a9-896a-34020c61ce12",
    "conceptCode": "TASA_SERVICIOS",
    "percentage": 100,
    "validFrom": "2026-11-01",
    "validUntil": "2027-08-31"
  }
}
```

**Observación:** Mismo requestId UUID que la solicitud correspondiente.

### M1 + M8 | updateExemptionStatus REJECTED

```json
{
  "eventId": "e73e8c79-7244-40fd-8dc8-6bb627e19dac",
  "eventType": "updateExemptionStatus",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-20T13:00:00-03:00",
  "sourceModule": "rentas",
  "correlationId": "c526401d-2224-4c41-9897-18130ca7c324",
  "causationId": null,
  "data": {
    "requestId": "8945e2df-edd3-46a4-8687-cc36c7adeb84",
    "citizenId": "2575b9ce-65b8-449f-873b-62ac40328948",
    "status": "REJECTED",
    "conceptCode": "TASA_SERVICIOS",
    "reason": "No reúne las condiciones"
  }
}
```

**Observación:** Solicitud independiente de ejemplo; no se inventa exemptionId.

### M1 | paymentPlanRequested

```json
{
  "eventId": "6a925e3c-1694-4c33-968f-936c8c4dded8",
  "eventType": "paymentPlanRequested",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-20T13:00:00-03:00",
  "sourceModule": "rentas",
  "correlationId": "b3b6bd19-40fa-468c-8cf4-56eb3363ecc4",
  "causationId": null,
  "data": {
    "requestId": "d94ec7c9-7538-4bde-b959-3034daa6b3cd",
    "citizenId": "2575b9ce-65b8-449f-873b-62ac40328948",
    "debtIds": [
      "503abb3c-f73c-4295-ab86-4fae0fe847b8",
      "b13bf815-37a3-446e-a1f5-cc9b67a751a7"
    ],
    "totalDebt": 200000,
    "installments": 6
  }
}
```

**Observación:** debtIds es una lista de UUID estables de las deudas, no códigos de boleta.

### M1 | updatePaymentPlanStatus GRANTED

```json
{
  "eventId": "989942e7-5c18-4abe-9acb-08dba61c66cc",
  "eventType": "updatePaymentPlanStatus",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-20T13:00:00-03:00",
  "sourceModule": "rentas",
  "correlationId": "b3b6bd19-40fa-468c-8cf4-56eb3363ecc4",
  "causationId": null,
  "data": {
    "requestId": "d94ec7c9-7538-4bde-b959-3034daa6b3cd",
    "citizenId": "2575b9ce-65b8-449f-873b-62ac40328948",
    "status": "GRANTED",
    "planId": "746cb726-e026-4605-aba4-7dbfde237aee",
    "installments": 6,
    "totalAmount": 220000
  }
}
```

**Observación:** Mismo requestId UUID que la solicitud de plan. planId UUID persistente.

### M1 | updatePaymentPlanStatus REJECTED

```json
{
  "eventId": "ae850978-f676-4472-bc47-c8f7f79fb083",
  "eventType": "updatePaymentPlanStatus",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-20T13:00:00-03:00",
  "sourceModule": "rentas",
  "correlationId": "c1107d93-af38-41a6-a0d2-1dedbed739f8",
  "causationId": null,
  "data": {
    "requestId": "cec6ec34-5327-4c71-bdb1-29c1eb31cad7",
    "citizenId": "2575b9ce-65b8-449f-873b-62ac40328948",
    "status": "REJECTED",
    "reason": "No cumple requisitos del plan"
  }
}
```

**Observación:** No emitir planId inexistente en un rechazo.

### M2 | updateTicketStatus RESOLVED / EXTERNAL_USER

```json
{
  "eventId": "b607a3e3-0760-4570-a86e-3f4874ba030b",
  "eventType": "updateTicketStatus",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-20T13:00:00-03:00",
  "sourceModule": "rentas",
  "correlationId": "f279d98e-3aad-4f8c-a4f4-68643f0820f8",
  "causationId": null,
  "data": {
    "ticketId": "7e6e74d7-1b55-4e6d-b4cc-cc7d072b2358",
    "updateType": "RESOLVED",
    "publicMessage": "El reclamo de pago fue resuelto.",
    "internalMessage": "Se verificó la imputación.",
    "progress": 100,
    "details": {
      "resolution": {
        "type": "ACTION_COMPLETED"
      }
    },
    "attachments": [],
    "updatedBy": {
      "type": "EXTERNAL_USER",
      "id": "bcd1db10-6a95-45ac-a080-e907880df6d2"
    },
    "updateOccurredAt": "2026-10-20T13:00:00-03:00"
  }
}
```

**Observación:** v1.78 exige UUID para ticketId y updatedBy.id; código USR-M5-12 ya no es válido.

### M2 | updateTicketStatus STARTED / SYSTEM

```json
{
  "eventId": "103b8731-a093-4aa2-8e86-b7359a0e83d4",
  "eventType": "updateTicketStatus",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-20T13:00:00-03:00",
  "sourceModule": "rentas",
  "correlationId": "f279d98e-3aad-4f8c-a4f4-68643f0820f8",
  "causationId": null,
  "data": {
    "ticketId": "7e6e74d7-1b55-4e6d-b4cc-cc7d072b2358",
    "updateType": "STARTED",
    "publicMessage": "Rentas inició la gestión.",
    "internalMessage": null,
    "progress": 0,
    "details": null,
    "attachments": [],
    "updatedBy": {
      "type": "SYSTEM",
      "id": null
    },
    "updateOccurredAt": "2026-10-20T13:00:00-03:00"
  }
}
```

**Observación:** Ejemplo automático; cuando hay persona real usar EXTERNAL_USER con UUID estable.

### M4 | paymentRegistered / PERMIT_FEE — OBJETIVO CONDICIONAL

```json
{
  "eventId": "1714c78d-35c2-43a8-9001-d3e664a40477",
  "eventType": "paymentRegistered",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-20T13:00:00-03:00",
  "sourceModule": "rentas",
  "correlationId": "2ced1f0c-551e-4e1a-8620-3572a46bbdc5",
  "causationId": null,
  "data": {
    "paymentId": "fbf74178-43fa-417c-8b53-4c475d3530b9",
    "debtId": "1a587698-84ac-4110-87b6-a2555c3aeaf1",
    "originType": "PERMIT_FEE",
    "originId": "5b377016-5bd8-42fe-adbf-15059d3e805c",
    "amountApplied": 50000,
    "paidAt": "2026-10-20T13:00:00-03:00",
    "remainingBalance": 0
  }
}
```

**Observación:** originId UUID solo si M4 publica id de tasa UUID y lo reconoce. Su contrato vigente ejemplifica FEE-001; NO activar publicación de originId UUID ficticio unilateralmente.

### M4 | debtSettled / PERMIT_FEE — OBJETIVO CONDICIONAL

```json
{
  "eventId": "74407fcc-25c2-4291-bcee-eaef97f6e489",
  "eventType": "debtSettled",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-20T13:00:00-03:00",
  "sourceModule": "rentas",
  "correlationId": "2ced1f0c-551e-4e1a-8620-3572a46bbdc5",
  "causationId": null,
  "data": {
    "debtId": "1a587698-84ac-4110-87b6-a2555c3aeaf1",
    "citizenId": null,
    "conceptCode": "TASA_HABILITACION",
    "originType": "PERMIT_FEE",
    "originId": "5b377016-5bd8-42fe-adbf-15059d3e805c",
    "settledAt": "2026-10-20T13:00:00-03:00",
    "remainingBalance": 0,
    "outstandingBalance": 0
  }
}
```

**Observación:** Mismo origen de M4 en originId; sin cambio de estructura compartida. citizenId null para obligación empresarial sin ciudadano.

### M4 | paymentReversed / PERMIT_FEE — OBJETIVO CONDICIONAL

```json
{
  "eventId": "0ec96cb1-7cda-43a1-a9b6-ef428f203f32",
  "eventType": "paymentReversed",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-20T13:00:00-03:00",
  "sourceModule": "rentas",
  "correlationId": "2ced1f0c-551e-4e1a-8620-3572a46bbdc5",
  "causationId": null,
  "data": {
    "paymentId": "fbf74178-43fa-417c-8b53-4c475d3530b9",
    "debtId": "1a587698-84ac-4110-87b6-a2555c3aeaf1",
    "originType": "PERMIT_FEE",
    "originId": "5b377016-5bd8-42fe-adbf-15059d3e805c",
    "reversedAmount": 50000,
    "reversedAt": "2026-10-20T13:00:00-03:00",
    "remainingBalance": 50000,
    "reason": "Reversión autorizada"
  }
}
```

**Observación:** Reutiliza paymentId, debtId y originId persistidos; solo cambia la ocurrencia de evento.

### M7 | paymentRegistered / TRAFFIC_FINE

```json
{
  "eventId": "03d69880-ff56-4c1f-ad20-9b19fce0e4de",
  "eventType": "paymentRegistered",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-20T13:00:00-03:00",
  "sourceModule": "rentas",
  "correlationId": "7b45e56f-3b1c-4095-90ba-23e933cd79a8",
  "causationId": null,
  "data": {
    "paymentId": "45759b20-dd92-462f-bef8-1fc5e54fd4ac",
    "debtId": "b13bf815-37a3-446e-a1f5-cc9b67a751a7",
    "originType": "TRAFFIC_FINE",
    "originId": "7e65a5a4-e6f1-4a0c-a6e6-38372d0c2efb",
    "infractionId": "7e65a5a4-e6f1-4a0c-a6e6-38372d0c2efb",
    "amountApplied": 75000,
    "paidAt": "2026-10-20T13:00:00-03:00",
    "remainingBalance": 0
  }
}
```

**Observación:** infractionId y originId idénticos al UUID recibido en infractionConfirmed.

### M7 | debtSettled / TRAFFIC_FINE

```json
{
  "eventId": "40c9a972-72a5-4776-a01f-c31f7a84d788",
  "eventType": "debtSettled",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-20T13:00:00-03:00",
  "sourceModule": "rentas",
  "correlationId": "7b45e56f-3b1c-4095-90ba-23e933cd79a8",
  "causationId": null,
  "data": {
    "debtId": "b13bf815-37a3-446e-a1f5-cc9b67a751a7",
    "citizenId": "2575b9ce-65b8-449f-873b-62ac40328948",
    "conceptCode": "TRAFFIC_FINE",
    "originType": "TRAFFIC_FINE",
    "originId": "7e65a5a4-e6f1-4a0c-a6e6-38372d0c2efb",
    "infractionId": "7e65a5a4-e6f1-4a0c-a6e6-38372d0c2efb",
    "settledAt": "2026-10-20T13:00:00-03:00",
    "remainingBalance": 0,
    "outstandingBalance": 0
  }
}
```

**Observación:** citizenId es UUID canónico M1, jamás DNI; conservamos infractionId, originType, ambos balances en cero.

### M7 | paymentReversed / TRAFFIC_FINE

```json
{
  "eventId": "300596a0-9c4d-43dd-9e82-f4e21aeba367",
  "eventType": "paymentReversed",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-20T13:00:00-03:00",
  "sourceModule": "rentas",
  "correlationId": "7b45e56f-3b1c-4095-90ba-23e933cd79a8",
  "causationId": null,
  "data": {
    "paymentId": "45759b20-dd92-462f-bef8-1fc5e54fd4ac",
    "debtId": "b13bf815-37a3-446e-a1f5-cc9b67a751a7",
    "originType": "TRAFFIC_FINE",
    "originId": "7e65a5a4-e6f1-4a0c-a6e6-38372d0c2efb",
    "infractionId": "7e65a5a4-e6f1-4a0c-a6e6-38372d0c2efb",
    "reversedAmount": 75000,
    "reversedAt": "2026-10-20T13:00:00-03:00",
    "remainingBalance": 75000,
    "reason": "Reversión autorizada"
  }
}
```

**Observación:** Identificadores estables; saldo restaurado coherente con reversión.

### M8 | overdueDebt

```json
{
  "eventId": "a872827f-d245-4414-87f2-216c1c85375c",
  "eventType": "overdueDebt",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-20T13:00:00-03:00",
  "sourceModule": "rentas",
  "correlationId": "8bce17e9-0cfd-4775-9f16-779a199c9af3",
  "causationId": null,
  "data": {
    "debtId": "503abb3c-f73c-4295-ab86-4fae0fe847b8",
    "citizenId": "2575b9ce-65b8-449f-873b-62ac40328948",
    "conceptCode": "TASA_SERVICIOS",
    "outstandingAmount": 85000,
    "dueDate": "2026-09-20"
  }
}
```

**Observación:** debtId y citizenId UUID; codes y fechas no son IDs.

### M8 | debtSettled / MUNICIPAL_TAX

```json
{
  "eventId": "ff725053-a8b6-445b-a9af-26e136b8f848",
  "eventType": "debtSettled",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-20T13:00:00-03:00",
  "sourceModule": "rentas",
  "correlationId": "8bce17e9-0cfd-4775-9f16-779a199c9af3",
  "causationId": null,
  "data": {
    "debtId": "503abb3c-f73c-4295-ab86-4fae0fe847b8",
    "citizenId": "2575b9ce-65b8-449f-873b-62ac40328948",
    "conceptCode": "TASA_SERVICIOS",
    "originType": "MUNICIPAL_TAX",
    "originId": "99aa2508-968e-4efa-98a7-347b91135c26",
    "settledAt": "2026-10-20T13:00:00-03:00",
    "remainingBalance": 0,
    "outstandingBalance": 0
  }
}
```

**Observación:** UN MISMO debtSettled compartido con M4 y M7, no una variante exclusiva M8.

## 5. Lógica de publicación y compatibilidad

1. Generar y persistir UUID estable de las entidades **de propiedad de M5**, incluso si la BD conserva otra PK interna (`BIGINT`, secuencia). Se permite columna `public_uuid UNIQUE NOT NULL` y una tabla de correspondencia en vez de migrar toda la PK; debe ser consistente y transaccional.
2. Para `citizenId`, **usar exclusivamente UUID canónico de M1**; correlacionar por el mapping oficial y no a partir de DNI. Los servicios que aún usan IDs viejos deben migrar antes de producir eventos con UUID canónico.
3. Para `ticketId`, usar el UUID de M2. Para `updatedBy.id`, usar UUID persistente del usuario/identidad real. Si el usuario actual tiene ID no-UUID, crear/mantener un mapping estable o enlazar su UUID de identidad autorizado, sin inventar un UUID por evento. Actor automático: SYSTEM + null.
4. Para multas M7: `infractionId` recibido y `originId` **son el mismo UUID**, conservar `originType=TRAFFIC_FINE`.
5. Para tasas/multas M4: mantener de forma persistente `originId` externo tal como llega. **BLOQUEADOR CONTRACTUAL**: M4 debe comunicar un UUID real para su `id` en `permitFeeGenerated` y `commercialFineGenerated` o aceptar un acuerdo formal de mapping. Mientras continúe mandando `FEE-001`/`FINE-001`, no prometer que esos `originId` serán UUID sin romper su correlación. El DTO puede soportar la forma legado sólo durante una transición pactada.
6. Para tributos propios: `originId` UUID real de la liquidación de M5.
7. `citizenId` nullable en `debtSettled` para titulares persona jurídica si el contrato lo permite; no asignar UUID de cualquier representante como si fuera deudor. Para `overdueDebt` de M8 (ejemplo dirigido a ciudadano), asegurar que haya `citizenId` canónico antes de publicar.
8. Si se imputa un pago a varias deudas, publicar **un `paymentRegistered` por asignación a deuda**, con el **mismo `paymentId` UUID**, distintos `debtId` y distintos `eventId`.
9. `debtSettled` requiere **saldo cero real**, después de persistir la cancelación. No alterar los balances por el cambio de IDs. El pago previo y la reversión deben mantener referencias estables.
10. Mantener eventos salientes desde M5 en una capa publisher/outbox común y schema acordado; cambios de DTO/serialización no deben alterar la lógica de cobro, cálculo de intereses, exenciones o planes.

## 6. Restricciones para Codex (obligatorias)

- **PRIMERO DIAGNOSTICAR** qué IDs ya son UUID en entidades, BD, DTOs, publishers y consumidores, y rastrear dónde se serializan.
- Implementar cambios locales y pruebas SIN cambiar nombres de evento/campos, los tipos de negocio ni el envelope.
- No modificar las clases consumidoras de eventos entrantes de otros módulos salvo compatibilidad concreta aprobada; no transformar IDs externos a un UUID inventado al consumir.
- **No desplegar ni cambiar unilateralmente contratos de M1/M4/M7/M8**. Marcar dependencias externas y proponer coordinación/versionado si el cambio de formato de un campo es incompatible con el contrato aceptado.
- Conservar schema compatible de los 3 eventos económicos unificados (`paymentRegistered`, `debtSettled`, `paymentReversed`) y tests de regresión para M4, M7 y M8.
- No cambiar el valor de `eventVersion` sin confirmación del schema/versionado aplicable de Core y consumidores.
- Mantener de forma segura idempotencia, outbox/publicación, auditoría y claves de negocio; no regenerar UUID al reintentar el mismo hecho.
- Si el sistema no puede obtener el UUID canónico de M1 o M4, registrar fallo/pending de integración controlado; no publicar un evento con identidad de otra persona o referencia no reconocible.

## 7. Pruebas de aceptación

1. Validar exactamente ocho claves de primer nivel (`eventId`, `eventType`, `eventVersion`, `occurredAt`, `sourceModule`, `correlationId`, `causationId`, `data`) y sourceModule `rentas`.
2. Validar UUIDv4 para `eventId` y UUID para los IDs en `data` marcados en el inventario (null únicamente donde el contrato lo permite). Rechazar datos prefijados si el nuevo acuerdo ya está activo.
3. Mismo ciudadano UUID M1 en exenciones, planes, deuda vencida, cancelaciones y en proyecciones de M2.
4. `exemptionRequested` consumido por M1/M8 y `updateExemptionStatus`: requestId y citizenId idénticos entre ambos hechos. Una sola publicación por eventType/ocurrencia.
5. `paymentPlanRequested`: cada deuda UUID de una entidad existente; `updatePaymentPlanStatus` reutiliza requestId, planId es UUID nuevo de plan persistido cuando GRANTED; REJECTED sin planId.
6. `updateTicketStatus`: `ticketId` UUID M2; updatedBy EXTERNAL_USER + UUID de persona, SYSTEM + null. No usar código `USR-M5-12`.
7. `paymentRegistered` de M7: `originId == infractionId`, y coincidencia con id del `infractionConfirmed` consumido; `paymentId`, `debtId` UUID. Deuda cancelada conserva saldo 0, reversión restaura saldo coherente.
8. `debtSettled` de M8: `debtId` idéntico al previo `overdueDebt`, `originId` UUID de liquidación de M5, balances cero, `citizenId` canónico.
9. M4: antes de pasar el test UUID `originId`, verificar ID UUID real emitido por M4; si no, reportar bloqueador contractual, no falsear test con UUID inventado.
10. Mensaje de origen M4 con `FEE-001`/`FINE-001` legado no debe correlacionarse con ID ficticio y generar falsas cancelaciones.
11. Un evento repetido mismo `eventId` se aplica una sola vez; otro hecho distinto recibe otro `eventId` aunque comparta paymentId/debtId.
12. Revisión de regresión para eventos económicos compartidos: mismo conjunto de campos y semántica en todos los `originType`.
13. Eventos no suscriptos o no destinados a M5 no deben provocar efecto en la BD de M5.
14. Identificadores numéricos históricos y mappings persistentes no deben perder registros o relación con auditoría.
15. Confirmación expresa del equipo receptor antes de actualizar validación estricta `format:uuid` para campos previamente documentados con otro formato.

## 8. Pendientes externos que NO se resuelven con un cambio en Codex

- **M1:** confirmar que `citizenId` canónico será UUID y que su contrato M1 acepta UUID en eventos que recibe de M5. También homogeneizar los IDs en los eventos que M1 produce (fuera de este documento).
- **M4:** acordar UUID reales de tasas `permitFeeGenerated.data.id` y multas `commercialFineGenerated.data.id`; actualizar las referencias `originId` de los tres eventos económicos. Revisar IDs de otras entidades propias de M4 si pactaron todos UUID.
- **M7:** confirmar que consume UUID de `paymentId`, `debtId` y `citizenId` como ID M1, no DNI, en los tres contratos compartidos; `infractionId` ya es UUID.
- **M8:** acordar actualización de ejemplos/schema para los 5 eventos bilaterales conforme a la propuesta UUID enviada; confirmar controles de `originId` compartidos con M4/M7.
- **Core M9:** si existían schemas de tipos previamente registrados que validaban otros formatos, coordinar actualización o nueva versión del contrato; el envelope sigue intacto.

## 9. Pedido sugerido a Codex

> Auditá todos los eventos **publicados por M5**, tomando este documento como CONTRATO OBJETIVO de identificadores. Estandarizá los IDs de entidades de nuestra propiedad a UUID **persistentes**, reutilizá los UUID externos oficiales, y mantené exactamente el envelope de Core y las estructuras de negocio. Respetá eventos unificados M4/M7/M8. No cambies contratos entrantes ni inventes UUID de M1 o M4. Implementá lo resoluble localmente y dejá bloqueadas/reportadas las dependencias que requieren acuerdo de otros módulos. Agregá pruebas para todos los eventos y entregá un reporte de archivos cambiados, tests y bloqueadores.
