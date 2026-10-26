# M5 RENTAS — Contratos de eventos para revisión de Codex


**Corte documental: 17/10/2026.** Referencias: `Eventos V1.75.docx` (M2); `Contrato_Eventos_M4_Habilitaciones.docx` (M4); acuerdo de Core comunicado por el equipo M9; contratos previos de M1, M7 y M8 trabajados con M5. Ejemplos JSON ficticios, no eventos observados en producción.

**Propósito:** comparar el código de M5 con contratos y propuestas, distinguiendo qué datos están confirmados y cuáles requieren aceptación bilateral. **No modificar el código hasta emitir un informe.**

## 0. Estado y alcance


| Interacción | Estado a efectos de auditoría | Qué verificar |
|---|---|---|
| **M2 ↔ M5** | **Alineado documentalmente** a envelope Core, contrato **v1.75 WIP** | Salida `updateTicketStatus`, entrada `ticketUpdated`, filtros y variantes |
| **M4 ↔ M5** | **Alineado documentalmente** con Core y con nombres/campos principales de M5; documento de trabajo | Salida de 3 eventos, entrada de 2 eventos con `cuit` + `currency` |
| **M7 ↔ M5** | **Propuesta M5 / confirmación M7 pendiente** | Entrada `infractionConfirmed` con su contrato anterior; salida unificada propuesta |
| **M1 ↔ M5** | **Solo salida M5 con Core definida**; entrada M1 pendiente de adaptar | No dar por confirmados envelopes entrantes de M1 |
| **M8 ↔ M5** | **Solo salida M5 con Core definida**; entrada M8 pendiente de adaptar | No dar por confirmados envelopes entrantes de M8 |

**Precisión:** «alineado documentalmente» no significa implementado, probado ni contrato congelado; M2 sigue marcado WIP y M4 como versión de trabajo.


## 1. Envelope oficial de Core — TODOS los eventos que publica M5


Claves de raíz **permitidas**: `eventId` (UUIDv4, obligatorio), `eventType` (inglés camelCase, obligatorio), `eventVersion` (opcional, predeterminado `1.0`), `occurredAt` (ISO-8601 con offset, obligatorio), `sourceModule` (slug del Core, obligatorio), `correlationId` (UUID, opcional), `causationId` (UUID, opcional), `data` (objeto, obligatorio). **No se permiten campos extra de raíz** como `timestamp`, `payload`, `producer`, `specVersion`, `subject`, `module` o `event`.

Para los eventos salientes M5 usar `sourceModule: "rentas"`, **no** `"M5"`; en `data.responsibleAreaId` se usa `"M5"` cuando corresponde. Se usa exactamente `occurredAt` con doble r. Un reenvío del mismo hecho conserva `eventId`; un hecho nuevo genera otro `eventId`. `correlationId` conserva el hilo de la operación; `causationId` puede referenciar el evento desencadenante, cuando existe, y ser `null` en una acción nueva. El campo `data` es específico por evento.

**No confundir obligación de formato con contrato de negocio**: que un payload sea válido para Core no significa que cada suscriptor lo haya aceptado.


## 2. M2 – Atención Ciudadana: ALINEADO (v1.75 WIP)


**Direcciones:** M5 publica `updateTicketStatus`; M5 consume `ticketUpdated`. M5 NO consume `ticketCreated` (M2 → M1). M2 es la única autoridad sobre el estado del ticket. `updateTicketStatus` nunca incluye `newStatus`/`previousStatus` ni necesita `publicId`.

### 2.1 M2 → M5: ticketUpdated / ROUTED


```json
{
  "eventId": "ceb59c25-f422-4a2d-97f1-8d4143b8c89f",
  "eventType": "ticketUpdated",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-17T10:00:01-03:00",
  "sourceModule": "atencion-ciudadana",
  "correlationId": "294e7015-0e6a-4bd1-8240-ce3148fd937d",
  "causationId": null,
  "data": {
    "ticketId": "550e8400-e29b-41d4-a716-446655440000",
    "publicId": "TK-2026-000123",
    "citizenId": "8f14e45f-ea5e-4e5d-bd3f-1c71f4f6a001",
    "isAnonymous": false,
    "responsibleAreaId": "M5",
    "updateType": "ROUTED",
    "currentStatus": "ROUTED",
    "currentPriority": "HIGH",
    "publicMessage": "El ticket fue derivado a Rentas.",
    "details": {
      "routing": {
        "requestType": "Informar un pago no registrado",
        "ticketType": "COMPLAINT",
        "summary": "Pago no registrado",
        "description": "Realicé un pago, pero la deuda continúa pendiente.",
        "formData": {},
        "location": null,
        "resolutionDueAt": "2026-10-24T18:00:00-03:00",
        "escalation": {
          "active": false,
          "reasonCode": null,
          "escalatedAt": null
        }
      }
    },
    "attachments": [],
    "updatedAt": "2026-10-17T10:00:00-03:00"
  }
}
```


**Handler obligatorio:** filtrar `data.responsibleAreaId == "M5"` antes de persistir datos del ticket. `data.ticketId` es UUID técnico; `publicId` es identificador visible. `requestType` es string dentro de `details.routing`. `isAnonymous: true` exige `citizenId: null`. Eventos válidos de otra área: ACK e ignorar.

### 2.2 M5 → M2: updateTicketStatus — 6 variantes


**Estos seis son variantes del MISMO `eventType`, no seis nombres de eventos.** Los ejemplos son independientes (no deben ejecutarse todos en secuencia sobre el mismo ticket). Valores de `updatedBy.type`: `EXTERNAL_USER` si actúa personal desde M5; `SYSTEM` si es un proceso automático. `updateOccurredAt` es obligatorio.

#### STARTED


```json
{
  "eventId": "2f131a0f-4dd0-4388-aef8-98f3befd6434",
  "eventType": "updateTicketStatus",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-17T10:05:00-03:00",
  "sourceModule": "rentas",
  "correlationId": "294e7015-0e6a-4bd1-8240-ce3148fd937d",
  "causationId": "ceb59c25-f422-4a2d-97f1-8d4143b8c89f",
  "data": {
    "ticketId": "550e8400-e29b-41d4-a716-446655440000",
    "updateType": "STARTED",
    "publicMessage": "Rentas inició el análisis del pago.",
    "internalMessage": null,
    "progress": null,
    "details": null,
    "attachments": [],
    "updatedBy": {
      "type": "EXTERNAL_USER",
      "id": "USR-M5-12"
    },
    "updateOccurredAt": "2026-10-17T10:05:00-03:00"
  }
}
```


M2: `ROUTED` → `IN_PROGRESS`. Opcional si se resolverá directamente.

#### PROGRESS


```json
{
  "eventId": "f6f248e3-ceaf-45bb-8643-cfbf2de53cfa",
  "eventType": "updateTicketStatus",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-17T10:20:00-03:00",
  "sourceModule": "rentas",
  "correlationId": "294e7015-0e6a-4bd1-8240-ce3148fd937d",
  "causationId": "2f131a0f-4dd0-4388-aef8-98f3befd6434",
  "data": {
    "ticketId": "550e8400-e29b-41d4-a716-446655440000",
    "updateType": "PROGRESS",
    "publicMessage": "Verificando la acreditación de la operación.",
    "internalMessage": null,
    "progress": 50,
    "details": null,
    "attachments": [],
    "updatedBy": {
      "type": "EXTERNAL_USER",
      "id": "USR-M5-12"
    },
    "updateOccurredAt": "2026-10-17T10:20:00-03:00"
  }
}
```


M2: admite `PROGRESS` desde `IN_PROGRESS`, con contenido útil. Si se envían STARTED y PROGRESS, procesar STARTED primero; no confiar en orden de llegada.

#### INFORMATION_REQUIRED


```json
{
  "eventId": "bc459827-f804-49dc-aefa-7b6722ab99bf",
  "eventType": "updateTicketStatus",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-17T10:25:00-03:00",
  "sourceModule": "rentas",
  "correlationId": "294e7015-0e6a-4bd1-8240-ce3148fd937d",
  "causationId": "f6f248e3-ceaf-45bb-8643-cfbf2de53cfa",
  "data": {
    "ticketId": "550e8400-e29b-41d4-a716-446655440000",
    "updateType": "INFORMATION_REQUIRED",
    "publicMessage": "Se requiere documentación para continuar.",
    "internalMessage": "No encontramos la referencia bancaria aportada.",
    "progress": null,
    "details": {
      "informationRequest": {
        "messageForCitizen": "Adjunte el comprobante completo de pago.",
        "requiredBy": null
      }
    },
    "attachments": [],
    "updatedBy": {
      "type": "EXTERNAL_USER",
      "id": "USR-M5-12"
    },
    "updateOccurredAt": "2026-10-17T10:25:00-03:00"
  }
}
```


M2: `ROUTED` o `IN_PROGRESS` → `PENDING_INFORMATION`. Obligatorio `details.informationRequest.messageForCitizen`; `requiredBy` opcional. Máximo una solicitud activa por ticket. **No existe `informationRequestId`** en el contrato.

#### RETURNED (rama alternativa)


```json
{
  "eventId": "d1a8ab1e-b821-4671-835b-62697510785c",
  "eventType": "updateTicketStatus",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-17T10:45:00-03:00",
  "sourceModule": "rentas",
  "correlationId": "294e7015-0e6a-4bd1-8240-ce3148fd937d",
  "causationId": "ceb59c25-f422-4a2d-97f1-8d4143b8c89f",
  "data": {
    "ticketId": "550e8400-e29b-41d4-a716-446655440000",
    "updateType": "RETURNED",
    "publicMessage": null,
    "internalMessage": "El caso corresponde a otra área.",
    "progress": null,
    "details": {
      "returnInfo": {
        "reasonCode": "REQUEST_TYPE_MISMATCH"
      }
    },
    "attachments": [],
    "updatedBy": {
      "type": "EXTERNAL_USER",
      "id": "USR-M5-12"
    },
    "updateOccurredAt": "2026-10-17T10:45:00-03:00"
  }
}
```


M2: `ROUTED` o `IN_PROGRESS` → `IN_REVIEW`. Enum cerrado: `INVALID_INFORMATION`, `REQUEST_TYPE_MISMATCH`, `INSUFFICIENT_CONTEXT`, `OTHER`. Requiere mensaje público o interno explicativo.

#### RESOLVED (rama alternativa)


```json
{
  "eventId": "22bf631a-62bf-4baa-803d-dabcc620dd44",
  "eventType": "updateTicketStatus",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-17T11:30:00-03:00",
  "sourceModule": "rentas",
  "correlationId": "294e7015-0e6a-4bd1-8240-ce3148fd937d",
  "causationId": "ceb59c25-f422-4a2d-97f1-8d4143b8c89f",
  "data": {
    "ticketId": "550e8400-e29b-41d4-a716-446655440000",
    "updateType": "RESOLVED",
    "publicMessage": "El pago fue verificado e imputado correctamente.",
    "internalMessage": "Se verificó conciliación contra registro de cobros.",
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
    "updateOccurredAt": "2026-10-17T11:30:00-03:00"
  }
}
```


M2: `ROUTED` o `IN_PROGRESS` → `RESOLVED`. Obliga `publicMessage` y `details.resolution.type` en el enum `ACTION_COMPLETED`, `REQUEST_FULFILLED`, `INQUIRY_ANSWERED`, `ACKNOWLEDGED`, `NO_FURTHER_ACTION_REQUIRED`. No se exige STARTED antes de RESOLVED.

#### REJECTED (rama alternativa)


```json
{
  "eventId": "bf5001a3-314c-4950-bb50-d667a066ed17",
  "eventType": "updateTicketStatus",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-17T10:50:00-03:00",
  "sourceModule": "rentas",
  "correlationId": "294e7015-0e6a-4bd1-8240-ce3148fd937d",
  "causationId": "ceb59c25-f422-4a2d-97f1-8d4143b8c89f",
  "data": {
    "ticketId": "550e8400-e29b-41d4-a716-446655440000",
    "updateType": "REJECTED",
    "publicMessage": "La solicitud no corresponde a una gestión válida de Rentas.",
    "internalMessage": null,
    "progress": null,
    "details": {
      "cancellation": {
        "reasonCode": "DOES_NOT_APPLY"
      }
    },
    "attachments": [],
    "updatedBy": {
      "type": "EXTERNAL_USER",
      "id": "USR-M5-12"
    },
    "updateOccurredAt": "2026-10-17T10:50:00-03:00"
  }
}
```


M2: `ROUTED` o `IN_PROGRESS` → `CANCELLED`. `details.cancellation.reasonCode` enum cerrado: `OUT_OF_SCOPE`, `DOES_NOT_APPLY`, `INVALID_REQUEST_TYPE`, `INVALID_DATA`, `REJECTED_BY_AREA`, `OTHER`. Requiere explicación pública o interna.

### 2.3 M2 → M5: ticketUpdated / INFORMATION_PROVIDED


```json
{
  "eventId": "5f388ef7-d6d1-4e24-9024-d03d6d7fc656",
  "eventType": "ticketUpdated",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-17T11:00:01-03:00",
  "sourceModule": "atencion-ciudadana",
  "correlationId": "294e7015-0e6a-4bd1-8240-ce3148fd937d",
  "causationId": "bc459827-f804-49dc-aefa-7b6722ab99bf",
  "data": {
    "ticketId": "550e8400-e29b-41d4-a716-446655440000",
    "publicId": "TK-2026-000123",
    "citizenId": "8f14e45f-ea5e-4e5d-bd3f-1c71f4f6a001",
    "isAnonymous": false,
    "responsibleAreaId": "M5",
    "updateType": "INFORMATION_PROVIDED",
    "currentStatus": "IN_PROGRESS",
    "currentPriority": "HIGH",
    "publicMessage": "El ciudadano aportó documentación adicional.",
    "details": {
      "informationResponse": {
        "message": "Adjunto comprobante de pago."
      }
    },
    "attachments": [
      {
        "fileName": "comprobante.pdf",
        "contentType": "application/pdf",
        "url": "https://archivos.ejemplo.org/comprobante.pdf",
        "sizeBytes": 302144
      }
    ],
    "updatedAt": "2026-10-17T11:00:00-03:00"
  }
}
```


**Validación:** requiere mensaje no vacío en `details.informationResponse.message` **o** al menos un `attachment`. `attachments` solo incluye archivos nuevos (referencias, no binarios); no se expone `internalMessage` en `ticketUpdated`. Si la solicitud salió de M5, M2 no devuelve un `ticketUpdated/INFORMATION_REQUIRED` espejo; sí puede mandar `INFORMATION_PROVIDED` al recibir la respuesta.

### 2.4 Otros updateType de ticketUpdated a reconocer


`STATUS_CHANGED`, `PROGRESS`, `PRIORITY_CHANGED`, `ESCALATION_CHANGED`, `INFORMATION_REQUIRED` (pedido originado en M2), `DUPLICATE_LINKED`, `REOPENED`, `RESOLVED` (resolución originada en M2), `CLOSED`, `CANCELLED`, `CONTENT_UPDATED`. **No todos se publican en todos los casos**. Reconocer y procesar únicamente variantes pertinentes al responsable M5 y al estado de gestión; `DUPLICATE` no se gestiona como ticket independiente. Los `ticketUpdated` derivados del mismo hecho comunicado por M5 en `updateTicketStatus` NO se emiten como eco. El schema oficial de M2 v1.75 dice acompañarse de `m2-eventos-v1.75-schemas.zip` (no se aportó el ZIP aquí); pedirlo para pruebas automatizadas de contrato.


## 3. M4 – Habilitaciones: ALINEADO documentalmente (octubre 2026)


M4 publica hacia M5 **solo** los eventos económicos `permitFeeGenerated` y `commercialFineGenerated`. **M5 no debe suscribirse a `permitUpdate` como dependencia de deuda.** M4 ahora incluye `cuit` y `currency` en ambos; el CUIT sirve para buscar contribuyente en proyección propia, comparándolo con `organizationRegistered.data.taxId` de M1 según contrato previo. **No acceder directamente a la BD de M4/M1.** El tipo `cuit` como string de 11 dígitos, y `currency: "ARS"`, son convenciones de ejemplo pendientes de confirmación de tipos/valores exactos con M4. El contrato M4 incorpora ya el envelope Core.


### 3.1 M4 → M5: permitFeeGenerated


```json
{
  "eventId": "67ea68d5-f946-4016-b563-abc6e81d29c1",
  "eventType": "permitFeeGenerated",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-17T09:00:00-03:00",
  "sourceModule": "habilitaciones",
  "correlationId": "5d59204d-f2ac-433a-ab76-75a40178a3a9",
  "causationId": null,
  "data": {
    "id": "FEE-001",
    "permitApplicationId": "PA-001",
    "establishmentId": "EST-001",
    "cuit": "<CUIT_DEMO_REDACTADO>",
    "amount": 50000,
    "currency": "ARS"
  }
}
```


**M5:** deduplicar por `eventId` y origen lógico `(sourceModule, data.id)`; buscar `cuit` en contribuyentes locales; crear obligación con `originType = PERMIT_FEE`, `originId = FEE-001`. Si no hay contribuyente, registrar pendiente y reintentar resolución, **sin adjudicar deuda a otro CUIT ni perder el mensaje**. Regla de vencimiento: no definida por M4 en este payload; aplicar política acordada de M5 o marcar pendiente de aclaración.

### 3.2 M4 → M5: commercialFineGenerated


```json
{
  "eventId": "6727f2d7-a5ea-48a5-9b37-63e12fa0df94",
  "eventType": "commercialFineGenerated",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-17T09:11:00-03:00",
  "sourceModule": "habilitaciones",
  "correlationId": "5d59204d-f2ac-433a-ab76-75a40178a3a9",
  "causationId": null,
  "data": {
    "id": "FINE-001",
    "establishmentId": "EST-001",
    "actId": "ACT-001",
    "cuit": "<CUIT_DEMO_REDACTADO>",
    "amount": 100000,
    "currency": "ARS",
    "reason": "Incumplimiento comercial",
    "decidedAt": "2026-10-17T09:10:00-03:00",
    "externalRef": "MULTA-2026-00123",
    "sourceViolationId": "V-001",
    "sourceModule": "M6"
  }
}
```


**M5:** crear obligación con `originType = COMMERCIAL_FINE`, `originId = FINE-001`. En `data`, `sourceViolationId` y `sourceModule` son **opcionales** y se incluyen solo si la multa deriva de una infracción M6; el `data.sourceModule = "M6"` NO se confunde con `sourceModule = "habilitaciones"` del envelope Core. `cuit` debe representar al obligado al pago (confirmación semántica pendiente).

### 3.3 M5 → M4: paymentRegistered (compartido con M7)


```json
{
  "eventId": "2eb6e27c-3e79-4f4f-9fd4-a2f690f85237",
  "eventType": "paymentRegistered",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-17T10:30:01-03:00",
  "sourceModule": "rentas",
  "correlationId": "5d59204d-f2ac-433a-ab76-75a40178a3a9",
  "causationId": null,
  "data": {
    "paymentId": "PAY-9001",
    "debtId": "DEBT-3001",
    "originType": "PERMIT_FEE",
    "originId": "FEE-001",
    "amountApplied": 50000,
    "paidAt": "2026-10-17T10:30:00-03:00",
    "remainingBalance": 0
  }
}
```


**M4 consume los siete campos listados.** Para multa comercial usar `originType=COMMERCIAL_FINE`, `originId=FINE-001`. Propuesta transversal M5: un evento por **imputación** a una deuda; puede repetirse `paymentId` con distinto `eventId` cuando se distribuye entre varias deudas.

### 3.4 M5 → M4: debtSettled (compartido con M7/M8)


```json
{
  "eventId": "6b169b38-a35b-4507-9aed-72f2a04bcf9f",
  "eventType": "debtSettled",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-17T10:30:03-03:00",
  "sourceModule": "rentas",
  "correlationId": "5d59204d-f2ac-433a-ab76-75a40178a3a9",
  "causationId": "2eb6e27c-3e79-4f4f-9fd4-a2f690f85237",
  "data": {
    "debtId": "DEBT-3001",
    "conceptCode": "TASA_HABILITACION",
    "originType": "PERMIT_FEE",
    "originId": "FEE-001",
    "settledAt": "2026-10-17T10:30:02-03:00",
    "remainingBalance": 0,
    "outstandingBalance": 0
  }
}
```


**M4 confirma esos campos**. Para el contrato único propuesto entre M4/M7/M8, `citizenId` es **campo opcional** cuando existe ciudadano asociado y M8 lo necesita. No inventar `citizenId` para organizaciones. `remainingBalance` y `outstandingBalance` valen ambos `0` al cancelar la deuda. No emitir `debtSettled` ante pago parcial.

### 3.5 M5 → M4: paymentReversed (compartido con M7)


```json
{
  "eventId": "e269c490-e39a-4135-82f2-cfe48c9fa1de",
  "eventType": "paymentReversed",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-18T11:00:01-03:00",
  "sourceModule": "rentas",
  "correlationId": "5d59204d-f2ac-433a-ab76-75a40178a3a9",
  "causationId": null,
  "data": {
    "paymentId": "PAY-9001",
    "debtId": "DEBT-3001",
    "originType": "PERMIT_FEE",
    "originId": "FEE-001",
    "reversedAmount": 50000,
    "reversedAt": "2026-10-18T11:00:00-03:00",
    "remainingBalance": 50000,
    "reason": "Reversión autorizada"
  }
}
```


**M4 confirma esos campos.** Para multas comerciales `originType = COMMERCIAL_FINE`, `originId = FINE-001`. Un pago revertido total o parcialmente se comunica por la **imputación afectada**; `reversedAmount` es monto aplicado revertido en esa deuda.

### 3.6 Propuesta transversal única para M4/M7/M8


El Core distribuye por `eventType`; **no genera un contrato distinto según destinatario**. Los nombres se mantienen: `paymentRegistered`, `debtSettled`, `paymentReversed`. Estructura propuesta: `originType` (`PERMIT_FEE` | `COMMERCIAL_FINE` | `TRAFFIC_FINE` | `MUNICIPAL_TAX`) + `originId` identifican obligación; `infractionId` se agrega **solo** si `TRAFFIC_FINE`; `citizenId` se agrega cuando está disponible y aplica a seguimiento M8. Para `debtSettled`, Core lo distribuye a todos los suscriptos: cada consumidor filtra por origen y relevancia. El conjunto común de campos está definido de nuestra parte; **la aceptación integral de campos opcionales y validadores estrictos por M7/M8 sigue pendiente**, no marcar esto como contrato bilateral final.


## 4. M7 – Tránsito: CONTRATO EN REVISIÓN (NO confirmado bilateralmente)


El envelope entrante antiguo documentado por M7 (`eventId`,`eventType`,`occurredAt`,`sourceModule`,`data`) es compatible con los campos mínimos de Core; **no hay un documento nuevo que confirme su adopción integral ni aceptación de salidas M5**. No tratar divergencias de esta sección como defectos definitivos sin confirmación del equipo M7.

### 4.1 M7 → M5: infractionConfirmed (referencia anterior)


```json
{
  "eventId": "e1ebae02-727c-4a21-92ff-6f00a29bef1a",
  "eventType": "infractionConfirmed",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-17T09:12:00-03:00",
  "sourceModule": "transito",
  "correlationId": "877f098d-c484-4656-84fe-f48363eb3e19",
  "causationId": null,
  "data": {
    "infractionId": "93fd4382-2343-4f3f-a127-9328b29fc520",
    "debtorId": "40123456",
    "debtorIdType": "DNI",
    "licensePlate": "AB123CD",
    "infractionType": "ILLEGAL_PARKING",
    "infractionDateTime": "2026-10-17T09:10:00-03:00",
    "baseAmount": 50000,
    "aggravatingFactors": [
      "REINCIDENCE"
    ],
    "finalAmount": 75000,
    "inspectorId": "INS-245",
    "location": {
      "street": "Av. Cabildo",
      "crossStreet": "Juramento",
      "latitude": -34.5621,
      "longitude": -58.4563
    }
  }
}
```


`eventVersion`, `correlationId` y `causationId` se agregan aquí como **envelope Core modelo**, aunque M7 no había confirmado aún su envío. El payload no contiene `citizenId` ni `dueDate`; M5 debe resolver deudor DNI/CUIT y gestionar vencimiento mediante regla propia o acuerdo.

### 4.2 M5 → M7: paymentRegistered (unificación propuesta)


```json
{
  "eventId": "15e6e962-efb6-449a-99f0-68cba6f5ee1c",
  "eventType": "paymentRegistered",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-17T12:00:01-03:00",
  "sourceModule": "rentas",
  "correlationId": "877f098d-c484-4656-84fe-f48363eb3e19",
  "causationId": null,
  "data": {
    "paymentId": "PAY-9200",
    "debtId": "DEBT-3100",
    "originType": "TRAFFIC_FINE",
    "originId": "93fd4382-2343-4f3f-a127-9328b29fc520",
    "infractionId": "93fd4382-2343-4f3f-a127-9328b29fc520",
    "amountApplied": 50000,
    "paidAt": "2026-10-17T12:00:00-03:00",
    "remainingBalance": 25000
  }
}
```


### 4.3 M5 → M7: debtSettled (unificación propuesta)


```json
{
  "eventId": "22bbb8de-087a-4b36-834b-86cf16a7abcd",
  "eventType": "debtSettled",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-17T12:15:01-03:00",
  "sourceModule": "rentas",
  "correlationId": "877f098d-c484-4656-84fe-f48363eb3e19",
  "causationId": "15e6e962-efb6-449a-99f0-68cba6f5ee1c",
  "data": {
    "debtId": "DEBT-3100",
    "citizenId": "8f14e45f-ea5e-4e5d-bd3f-1c71f4f6a001",
    "conceptCode": "MULTA_TRANSITO",
    "originType": "TRAFFIC_FINE",
    "originId": "93fd4382-2343-4f3f-a127-9328b29fc520",
    "infractionId": "93fd4382-2343-4f3f-a127-9328b29fc520",
    "settledAt": "2026-10-17T12:15:00-03:00",
    "remainingBalance": 0,
    "outstandingBalance": 0
  }
}
```


### 4.4 M5 → M7: paymentReversed (unificación propuesta)


```json
{
  "eventId": "e90552c4-9c59-4c74-bd9f-18c9ff2a4a5d",
  "eventType": "paymentReversed",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-18T11:00:01-03:00",
  "sourceModule": "rentas",
  "correlationId": "877f098d-c484-4656-84fe-f48363eb3e19",
  "causationId": null,
  "data": {
    "paymentId": "PAY-9200",
    "debtId": "DEBT-3100",
    "originType": "TRAFFIC_FINE",
    "originId": "93fd4382-2343-4f3f-a127-9328b29fc520",
    "infractionId": "93fd4382-2343-4f3f-a127-9328b29fc520",
    "reversedAmount": 50000,
    "reversedAt": "2026-10-18T11:00:00-03:00",
    "remainingBalance": 50000,
    "reason": "Reversión autorizada"
  }
}
```


**ACLARACIÓN:** los tres payloads de M5 hacia M7 todavía son propuesta; validar suscripción y aceptación de `originType`, `originId`, `infractionId`, `amountApplied`, `remainingBalance`, campos extras, etc. Los ejemplos son independientes y no representan una secuencia contable completa del mismo pago.

## 5. M1 – Ciudadanos: salientes M5 en Core; entrantes PENDIENTES


**Solo verificar del lado M5 la publicación de estos cuatro eventos con envelope Core.** La versión anterior recibida de M1 tenía `producer` y `subject` en raíz y no debe interpretarse como formato definitivo aceptado por Core. También está pendiente confirmar consumo y correlación con expedientes. Los nombres/datos siguientes son el contrato de trabajo M5, no la versión bilateral congelada.

### 5.1 exemptionRequested


```json
{
  "eventId": "c313bb47-e368-4d91-bf8c-b8d3f67ab3bc",
  "eventType": "exemptionRequested",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-17T14:00:00-03:00",
  "sourceModule": "rentas",
  "correlationId": "9597d9e8-26d7-4840-b831-2df29c9bb6ef",
  "causationId": null,
  "data": {
    "requestId": "exr-00600",
    "citizenId": "8f14e45f-ea5e-4e5d-bd3f-1c71f4f6a001",
    "conceptCode": "TASA_SERVICIOS",
    "reason": "Situación socioeconómica",
    "requestedPercentage": 100,
    "requestedFrom": "2026-09-01",
    "requestedUntil": "2027-08-31"
  }
}
```


### 5.2 updateExemptionStatus


```json
{
  "eventId": "6bb6a3a8-ba4e-4b8c-ac1f-dd80a1bce685",
  "eventType": "updateExemptionStatus",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-17T14:00:00-03:00",
  "sourceModule": "rentas",
  "correlationId": "9597d9e8-26d7-4840-b831-2df29c9bb6ef",
  "causationId": null,
  "data": {
    "requestId": "exr-00600",
    "citizenId": "8f14e45f-ea5e-4e5d-bd3f-1c71f4f6a001",
    "status": "APPROVED",
    "exemptionId": "ex-00700",
    "conceptCode": "TASA_SERVICIOS",
    "percentage": 100,
    "validFrom": "2026-09-01",
    "validUntil": "2027-08-31"
  }
}
```


### 5.3 paymentPlanRequested


```json
{
  "eventId": "e897044c-0c03-4320-899b-5143b7e85a75",
  "eventType": "paymentPlanRequested",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-17T14:00:00-03:00",
  "sourceModule": "rentas",
  "correlationId": "8725fbe5-bea3-4a50-95d8-1138daf0b7b2",
  "causationId": null,
  "data": {
    "requestId": "ppr-00800",
    "citizenId": "8f14e45f-ea5e-4e5d-bd3f-1c71f4f6a001",
    "debtIds": [
      "debt-03001",
      "debt-03002"
    ],
    "totalDebt": 200000,
    "installments": 6
  }
}
```


### 5.4 updatePaymentPlanStatus


```json
{
  "eventId": "42748612-f890-43bd-a71e-74e7e77febec",
  "eventType": "updatePaymentPlanStatus",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-17T14:00:00-03:00",
  "sourceModule": "rentas",
  "correlationId": "8725fbe5-bea3-4a50-95d8-1138daf0b7b2",
  "causationId": null,
  "data": {
    "requestId": "ppr-00800",
    "citizenId": "8f14e45f-ea5e-4e5d-bd3f-1c71f4f6a001",
    "status": "GRANTED",
    "planId": "plan-00850",
    "installments": 6,
    "totalAmount": 220000
  }
}
```


`updateExemptionStatus` rechazado: `data.status="REJECTED"`, enviar `reason`, omitir `exemptionId` y porcentajes/fechas concedidas. `updatePaymentPlanStatus` rechazado: `status="REJECTED"`, enviar `reason`, omitir `planId` y condiciones aprobadas. Una solicitud `paymentPlanRequested` y la resolución posterior comparten hilo de correlación; lo mismo para exenciones. **Confirmar tipos/IDs finales con M1.**

## 6. M8 – Desarrollo Social: salientes M5 en Core; entrante PENDIENTE


**Solo verificar del lado M5**: publica `overdueDebt`, `debtSettled`, `exemptionRequested` y `updateExemptionStatus` con el envelope de Core; los dos eventos de exención son los MISMOS que para M1, no se emiten copias por destinatario. M8 antes documentó `socialBenefitUpdated` con `timestamp`/`payload` y slug `social-development`; el paso de sus eventos entrantes al envelope Core y slug `desarrollo-social` está por confirmar.

### 6.1 overdueDebt


```json
{
  "eventId": "2bd0466a-9dcf-4f00-ae7c-c13d97240805",
  "eventType": "overdueDebt",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-17T15:00:00-03:00",
  "sourceModule": "rentas",
  "correlationId": "3231780d-4bf9-4d18-a63d-0757eec01efe",
  "causationId": null,
  "data": {
    "debtId": "debt-03200",
    "citizenId": "8f14e45f-ea5e-4e5d-bd3f-1c71f4f6a001",
    "conceptCode": "TASA_SERVICIOS",
    "outstandingAmount": 85000,
    "dueDate": "2026-10-10"
  }
}
```


### 6.2 debtSettled (MUNICIPAL_TAX, contrato común propuesto)


```json
{
  "eventId": "c37cacc4-1a0a-4ece-a6cb-4538e7997acf",
  "eventType": "debtSettled",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-17T15:20:01-03:00",
  "sourceModule": "rentas",
  "correlationId": "3231780d-4bf9-4d18-a63d-0757eec01efe",
  "causationId": null,
  "data": {
    "debtId": "debt-03200",
    "citizenId": "8f14e45f-ea5e-4e5d-bd3f-1c71f4f6a001",
    "conceptCode": "TASA_SERVICIOS",
    "originType": "MUNICIPAL_TAX",
    "originId": "LIQ-01001",
    "settledAt": "2026-10-17T15:20:00-03:00",
    "remainingBalance": 0,
    "outstandingBalance": 0
  }
}
```


`exemptionRequested` / `updateExemptionStatus`: reutilizar **exactamente los tipos de evento** de §5.1 y §5.2. No generar dos eventos con el mismo hecho para distintos destinatarios; Core reparte una sola publicación a los suscriptores. M8 aún debe confirmar tolerancia a campos comunes extra y al caso `citizenId` nulo para deudas de organizaciones.

## 7. Suscripciones / publicaciones que debe revisar Codex


| Flujo | eventType | Estado contrato |
|---|---|---|
| **M5 consume** | `ticketUpdated` | M2 v1.75 alineado |
| **M5 consume** | `permitFeeGenerated`, `commercialFineGenerated` | M4 octubre alineado |
| **M5 consume** | `infractionConfirmed` | M7 pendiente confirmación final |
| **M5 consume** | `citizenUpdated`, `organizationRegistered`, `representationGranted`, `representationExpired` | M1 envelope pendiente |
| **M5 consume** | `socialBenefitUpdated` | M8 envelope pendiente |
| **M5 publica** | `updateTicketStatus` | M2 v1.75 alineado |
| **M5 publica** | `paymentRegistered`, `debtSettled`, `paymentReversed` | M4 alineado en nombres/campos; contrato unificado completo pendiente M7/M8 |
| **M5 publica** | `exemptionRequested`, `updateExemptionStatus`, `paymentPlanRequested`, `updatePaymentPlanStatus`, `overdueDebt` | Envelope Core definido M5; aceptación de consumidores M1/M8 por confirmar |

Infraestructura declarada por Core: publicar mediante su integración en `muni.inbox`; consumir desde la cola `q.rentas` que crea el Core (no redeclararla con opciones distintas). Una sola cola puede contener varios tipos; despachar por `eventType`. Registrar suscripciones vía `POST /api/v1/subscriptions` y catálogo vía `POST /api/v1/publications`. Usar ACK para irrelevantes válidos, idempotencia persistida en M5, NACK `requeue=false` para fallas recuperables, sin confiar en orden de llegada. El Core puede deduplicar publicaciones según su FAQ, pero **M5 igualmente debe deduplicar al consumir**.


## 8. Checklist de auditoría para Codex


1. Localizar publishers/consumers/DTOs/adaptadores/configuraciones RabbitMQ y mapear cada `eventType` contra clases/archivos concretos.
2. Verificar **primero M2 y M4** (documentos disponibles): payload y envelope campo a campo, casing, tipos, obligatoriedad, enums, fechas, estados y filtros.
3. Revisar si `updateTicketStatus` publica seis variantes, si usa `sourceModule="rentas"`, `data.ticketId` UUID, `updatedBy=EXTERNAL_USER|SYSTEM`, `updateOccurredAt`, sin `producer`, `subject`, `specVersion` ni estados nuevos.
4. Revisar consumo de M2: `ticketUpdated` se filtra `responsibleAreaId="M5"`, acepta tickets anónimos, `publicId`, `details.routing` y `INFORMATION_PROVIDED`; no espera eventos espejo. No crear `informationRequestId` interoperable.
5. Revisar consumo de M4: `cuit` y `currency` presentes, mapeo `cuit` ↔ proyección local de `organizationRegistered.data.taxId`, idempotencia por `eventId` e ID externo, creación pendiente si falta contribuyente y NO llamadas directas a BD de terceros.
6. Revisar salientes M4: `paymentRegistered`, `debtSettled`, `paymentReversed`, `originType` con `PERMIT_FEE` o `COMMERCIAL_FINE`, `originId` FEE/FINE recibido, saldos/importe imputado.
7. Revisar idempotencia real en base de datos; reintentos/ACK/NACK/DLQ; condiciones de concurrencia y desorden; no duplicar deudas ni pagos ante reentregas.
8. Revisar enum único `originType` y payload unificado entre M4/M7/M8, **señalando explícitamente los campos que todavía no fueron aceptados por cada consumidor**.
9. Entregar tabla **Módulo | Evento | Dirección | Confirmado/propuesta | Implementación | Archivo | Diferencias | Prioridad | Corrección sugerida**.
10. **NO modificar código** hasta recibir autorización. Si el repositorio no contiene una parte, reportar «no encontrada» en vez de asumir implementación.


## 9. Preguntas / acuerdos aún abiertos


- **M4:** confirmar tipo de `cuit` (sugerido string 11 dígitos), valores de `currency`, que el CUIT sea del sujeto obligado al pago y admisión de campos opcionales del contrato unificado. Fecha de vencimiento de tasa/multa no está en payload.
- **M7:** confirmar versión Core, suscripción a `paymentRegistered`, `debtSettled`, `paymentReversed`, contrato de unificación y significado de `infractionId`.
- **M1/M8:** confirmar que reemplazaron sus envelopes de entrada a M5 por Core, y que sus consumidores aceptan nuestras salidas.
- **Core:** confirmar definitivamente ortografía `occurredAt` (el JSON oficial la usa con doble r); coordinar si hay diferencia documental sobre deduplicación en Core, sin dejar de implementar deduplicación propia.
- **M2:** contrato v1.75 aún WIP; solicitar el paquete de JSON Schemas v1.75 si se desea validación automática exacta.
