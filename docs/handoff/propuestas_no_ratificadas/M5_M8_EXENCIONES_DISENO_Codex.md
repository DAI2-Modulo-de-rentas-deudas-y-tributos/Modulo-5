# M5 Rentas ↔ M8 Desarrollo Social — Contratos y reglas de implementación

**Fecha de revisión:** 20/10/2026
**Destinatario:** Codex, para auditar e implementar en el repositorio del Módulo 5 (Rentas).
**Documento externo de referencia:** `EventosDesarrolloSocialConEnvelope.pdf`, 11 páginas, enviado por M8.
**Alcance:** integración entre M5 y M8 mediante Core; recepción de beneficios sociales, emisión de eventos de deuda/exenciones y aplicación tributaria de `TAX_EXEMPTION`.

> **Instrucción principal para Codex:** inspeccionar el código existente y **aplicar las modificaciones necesarias** en el backend, persistencia, seguridad y frontend de M5. No modificar el código de M8, M1, M2 ni Core. Reutilizar las convenciones existentes del repositorio. Escribir y ejecutar pruebas. Reportar cambios, pruebas y decisiones que requieran intervención humana.

## 0. Alcance, autoridad y estado de cada definición

Para no confundir un contrato externo con decisiones propias:

- **[M8 / DOCUMENTADO]**: los nombres y ejemplos de envelope/payload de `socialBenefitUpdated` (págs. 2–4) y los cuatro eventos que consume M8 desde M5 (págs. 7–9). **Estos campos externos no se renombran.**
- **[CORE / DOCUMENTADO]**: estructura común del envelope, distribución por `eventType`, RabbitMQ, idempotencia, reintentos y suscripciones. Se usa el slug `desarrollo-social` para M8 y `rentas` para M5.
- **[M5 / DECISIÓN DE NEGOCIO]**: determinar tributo y porcentaje mediante reglas internas aprobadas por Supervisor, aplicar al liquidar, elegir el mayor porcentaje de exenciones coincidentes, no recalcular automáticamente liquidaciones emitidas y manejar estados y eventos fuera de orden.
- **[ACUERDO PREVIO DEL EQUIPO]**: compatibilidad de identificadores de ciudadanos y aceptación del estado `REJECTED` para `updateExemptionStatus` fueron informadas como resueltas por el equipo. El PDF de M8 incluye un ejemplo `APPROVED`, no un ejemplo completo de `REJECTED`; conservar para este último el payload que ya definió M5, sin afirmar que el PDF lo muestra.
- **[PROPUESTA DE IMPLEMENTACIÓN]**: nombres de clases/tablas/endpoints internos de este archivo; se pueden adaptar al diseño existente. No son nuevos contratos externos.

**No agregar eventos externos nuevos.** **No agregar a `socialBenefitUpdated` campos como `conceptCode` o `percentage`**, porque M8 no los manda. **No reinterpretar el beneficio social recibido como una solicitud local de exención.**

---

## 1. Matriz definitiva de integración M5 ↔ M8

| Dirección | `eventType` | ¿Qué significa? | Estado |
|---|---|---|---|
| M8 → M5 | `socialBenefitUpdated` | Actualización del estado de beneficio social; M5 analiza `TAX_EXEMPTION`. | Documentado por M8 |
| M5 → M8 | `overdueDebt` | Deuda vencida de un ciudadano. | Documentado por M8 |
| M5 → M8 | `debtSettled` | Deuda cancelada; **mismo tipo y estructura unificada** para M4/M7/M8. | Documentado por M8 |
| M5 → M8 | `exemptionRequested` | Solicitud de exención originada en Rentas. | Documentado por M8 |
| M5 → M8 | `updateExemptionStatus` | Resultado de solicitud de exención gestionada por Rentas. | `APPROVED` ejemplificado; `REJECTED` según contrato acordado por M5 |

**NO son eventos M8 → M5**: `socialDocumentationRequested` (M8 → M1), `updateTicketStatus` emitido por M8 (M8 → M2), `socialDocumentationDelivered` y `citizenUpdated` (M1 → M8), y `ticketUpdated` de M2 dirigido a otras áreas. La integración propia de tickets M5 ↔ M2 se implementa por separado.

**Core:** M5 se suscribe a `socialBenefitUpdated` (además de las demás suscripciones que ya tenga). M5 declara como publicaciones `overdueDebt`, `debtSettled`, `exemptionRequested`, `updateExemptionStatus`; **no publicar una copia por cada destinatario**. `debtSettled` también lo consumen otros módulos; Core distribuye un solo evento según suscripciones.

---

## 2. Envelope común obligatorio de Core

Formato para publicaciones de M5 y para deserializar mensajes de M8:

```json
{
  "eventId": "b1e4c8a2-7f30-4d91-8a62-15e9c0f3d742",
  "eventType": "socialBenefitUpdated",
  "eventVersion": "1.0",
  "occurredAt": "2026-08-23T10:00:00-03:00",
  "sourceModule": "desarrollo-social",
  "correlationId": null,
  "causationId": null,
  "data": {}
}
```

- `eventId`: UUIDv4, clave de deduplicación; reintentos del mismo hecho deben conservarlo.
- `eventType`: nombre exacto en camelCase.
- `eventVersion`: versión del contrato (`"1.0"` en los ejemplos).
- `occurredAt`: **doble r**; fecha ISO-8601 con `Z` u offset (`-03:00`).
- `sourceModule`: slug exacto registrado en Core (`"rentas"` al publicar M5, `"desarrollo-social"` al recibir M8).
- `correlationId` y `causationId`: UUID o `null` cuando corresponda. Si hay evento causante, preservar hilo y referencia.
- `data`: objeto del payload de negocio.
- **No publicar** campos extra de primer nivel (`timestamp`, `payload`, `producer`, `subject`, `specVersion`, `module`, `event`, etc.).
- El Core puede agregar metadatos al entregar/almacenar; el consumidor debe tolerar los campos técnicos que provengan de Core sin confundirlos con los campos enviados por el productor.

**Tipos de identificadores:** los IDs de negocio como `"c-00123"`, `"ben-00789"`, `"prog-00789"`, `"debt-03200"` son **strings en los ejemplos de M8**; no imponer UUID a esos campos solo porque `eventId` sea UUID.

---

## 3. CONTRATO ENTRANTE: M8 → M5 `socialBenefitUpdated`

### 3.1 Ejemplo completo APPROVED — copiado del contrato de M8 (págs. 2–3)

```json
{
  "eventId": "b1e4c8a2-7f30-4d91-8a62-15e9c0f3d742",
  "eventType": "socialBenefitUpdated",
  "eventVersion": "1.0",
  "occurredAt": "2026-08-23T10:00:00-03:00",
  "sourceModule": "desarrollo-social",
  "correlationId": null,
  "causationId": null,
  "data": {
    "benefitId": "ben-00789",
    "citizenId": "c-00123",
    "applicationId": "sol-00456",
    "programId": "prog-00789",
    "programName": "Subsidio Habitacional 2026",
    "benefits": [
      { "type": "TAX_EXEMPTION" },
      { "type": "FOOD_ASSISTANCE", "amount": 15000 },
      { "type": "UTILITY_SUBSIDY", "amount": 5000 }
    ],
    "status": "APPROVED",
    "reason": null,
    "startDate": "2026-09-01",
    "endDate": "2027-09-01",
    "updatedAt": "2026-08-23T10:00:00-03:00"
  }
}
```

**Campos del negocio a interpretar:** `benefitId`, `citizenId`, `applicationId`, `programId`, `programName`, `benefits[]`, `status`, `reason`, `startDate`, `endDate`, `updatedAt`. M8 explica que `benefitId`, `citizenId`, `applicationId`, `programId`, `benefits` y `status` se mantienen en los cuatro estados; otros campos y nulabilidad varían según ejemplo. **No inferir un JSON Schema formal más estricto del que realmente acordaron.**

### 3.2 Estados — variantes documentadas (págs. 3–4)

Los siguientes fragmentos reemplazan **solo esos campos dentro de `data`** y no son nuevos `eventType`.

**Aprobado:**

```json
{ "status": "APPROVED", "reason": null, "startDate": "2026-09-01", "endDate": "2027-09-01" }
```

**Rechazado:**

```json
{ "status": "REJECTED", "reason": "No cumple los requisitos", "startDate": null, "endDate": null }
```

**Suspendido:**

```json
{ "status": "SUSPENDED", "reason": "Documentación pendiente", "startDate": null, "endDate": null }
```

**Finalizado:**

```json
{ "status": "FINALIZED", "reason": "Finalización del beneficio", "startDate": null, "endDate": "2027-09-01" }
```

**Implementar los cuatro estados**, `reason` nulo/no nulo, fechas ausentes/nulas según corresponda y múltiples entradas en `benefits` sin asumir que todas son tributarias.

### 3.3 Procesamiento esperado en Rentas [M5 / DECISIÓN]

1. Recibir el mensaje de Core; validar nombre, versión soportada, slug de productor, envelope y `data`.
2. Comprobar idempotencia por `eventId` antes de producir efectos.
3. Resolver el contribuyente local por `data.citizenId`, usando la proyección de M1 o equivalencia ya acordada. Nunca crear un contribuyente fantasma ni adjudicar a un tercero.
4. Buscar `TAX_EXEMPTION` en `data.benefits`. `FOOD_ASSISTANCE` y `UTILITY_SUBSIDY` **no modifican deuda ni liquidaciones**.
5. Persistir/actualizar el beneficio externo de modo trazable (`benefitId` como clave externa, `programId`, estado, vigencia, `reason`, `updatedAt`, origen M8).
6. Si hay `TAX_EXEMPTION`, evaluar reglas locales por `programId` cuando el beneficio esté `APPROVED`; **no emitir ni recalcular automáticamente liquidaciones solo por recibir el evento**.
7. Para `REJECTED`, `SUSPENDED`, `FINALIZED`: conservar estado e historial; evitar aplicar el beneficio a futuras liquidaciones mientras no esté aprobado y vigente.
8. Si el `programId` no tiene mapeo autorizado o no se pudo resolver contribuyente, registrar estado **pendiente de parametrización / identificación** y gestionar posteriormente. No asignar porcentaje por defecto ni perder el evento.
9. Rechazar o aislar payload mal formado conforme a política de errores; para fallas transitorias usar reintento gestionado por Core, sin bucles de `requeue=true`.
10. Una vez confirmado un evento, no volver a aplicarlo en reentregas. No generar nuevos eventos `exemptionRequested` ni `updateExemptionStatus` como “respuesta automática” al `socialBenefitUpdated` de M8.

### 3.4 Estados y tiempos [M5 / DECISIÓN]

| `data.status` | Estado local | Regla de cálculo en nuevas liquidaciones |
|---|---|---|
| `APPROVED` | Aprobado | Elegible **solo** si el programa tiene regla aprobada y la fecha tributaria de la nueva liquidación cae dentro de la vigencia recibida. |
| `REJECTED` | Rechazado | No aplicar beneficio. Conservar motivo. |
| `SUSPENDED` | Suspendido | No aplicar mientras permanezca suspendido. `updatedAt` es la referencia del momento de suspensión conocida por M5; no inventar `startDate`. |
| `FINALIZED` | Finalizado | No aplicar a nuevas liquidaciones; conservar la fecha `endDate` si M8 la remitió. |

**Fechas de vigencia:** comparar fechas de calendario (`LocalDate`) en la zona fiscal definida para Rentas. Para la implementación inicial, usar una **fecha tributaria explícita de la obligación** (p. ej., fecha de devengamiento o inicio del período liquidado, según el modelo que exista). No utilizar arbitrariamente la fecha de recepción del evento. El rango `startDate`–`endDate` es inclusivo para la regla de ejemplo. Si un evento `APPROVED` carece de una fecha necesaria para determinar la vigencia con seguridad, marcarlo para revisión y no aplicar automáticamente hasta tener una regla clara.

**Orden de eventos:** para el mismo `benefitId` solo aplicar una actualización si `data.updatedAt` es posterior a la ya persistida. Si es anterior, guardar auditoría de evento tardío e ignorar su efecto. Si es igual pero el contenido cambia, registrar conflicto y requerir revisión, sin sobrescribir a ciegas. Nunca revivir con un `APPROVED` tardío un beneficio ya `SUSPENDED` o `FINALIZED`. El `eventId` resuelve duplicados exactos; `benefitId + updatedAt` resuelve orden del negocio.

---

## 4. CONFIGURACIÓN TRIBUTARIA DE EXENCIONES SOCIALES — DECISIÓN DE M5

### 4.1 ¿Quién determina tributo y porcentaje?

**M8** es propietario del beneficio social: comunica programa, estado y vigencia. **M5** es propietario de la tributación: decide el `conceptCode`, porcentaje y las condiciones mediante reglas locales autorizadas.

**M8 NO publica** en `socialBenefitUpdated` el `conceptCode` ni el porcentaje de `TAX_EXEMPTION`; no agregarlos al DTO entrante como requisitos ni solicitar que M8 los envíe para esta versión.

### 4.2 Regla local propuesta

Datos ficticios para la prueba del TP:

```json
{
  "programId": "prog-00789",
  "programName": "Subsidio Habitacional 2026",
  "benefitType": "TAX_EXEMPTION",
  "conceptCode": "TASA_SERVICIOS",
  "exemptionPercentage": 100,
  "enabled": true,
  "approvalStatus": "APPROVED"
}
```

- Este mapeo **NO viene de M8** y **NO es una regla oficial del municipio**; es una configuración de ejemplo decidida en M5.
- Un programa puede relacionarse con uno o varios conceptos tributarios mediante reglas distintas, si el modelo lo soporta.
- `exemptionPercentage` válido de 0 a 100; usar tipo numérico decimal preciso, sin redondeos monetarios inesperados.
- Las reglas las propone Personal de Rentas y **las autoriza el Supervisor** antes de activarse. Respetar las HU y permisos existentes de configuración tributaria; no crear una segunda vía para saltar esa autorización.
- Guardar versión, fecha de vigencia de regla y autor de propuesta/aprobación; las liquidaciones guardan la versión/regla aplicada como instantánea, sin retroactividad automática.
- Una regla inactiva, no aprobada, inexistente o fuera de vigencia **no aplica**.

### 4.3 Cálculo cuando se emite una nueva liquidación

1. Obtener contribuyente, concepto, base imponible, período y fecha tributaria.
2. Calcular monto bruto con las reglas de liquidación vigentes.
3. Buscar beneficios **de ese contribuyente**, provenientes de M8, que incluyan `TAX_EXEMPTION` y estén **`APPROVED` y vigentes**.
4. Obtener reglas locales **autorizadas** donde coincidan `programId` + `conceptCode` + fecha de regla.
5. Considerar también las exenciones locales de Rentas **ya aprobadas**, si existen y aplican al mismo concepto/período.
6. Si hay varias exenciones válidas para el mismo concepto, **aplicar solamente la de mayor porcentaje** (no sumar porcentajes). Si empatan, mantener criterio determinista y referencias auditables, sin duplicar descuentos.
7. Importe exento = monto bruto × porcentaje / 100. Importe neto = máximo(0, bruto − exento), respetando la política monetaria del sistema.
8. Registrar liquidación con importe original, porcentaje, descuento, importe neto y referencias al beneficio `benefitId`, programa y versión de regla utilizada.
9. Una liquidación **ya emitida no se reescribe automáticamente** si después llega una aprobación, suspensión o cambio de regla. Para revisar históricos se utiliza el procedimiento explícito de ajuste con autorización.
10. Con importe neto 0, conservar constancia de la liquidación/exención pero **no crear una deuda exigible de monto cero**, según el modelo actual de M5.

**Ejemplo de cálculo:** bruto $80.000, regla de programa 100 % para `TASA_SERVICIOS` → descuento $80.000 → neto $0. Un tributo distinto no recibe el descuento. Otro programa sin mapeo aprobado no recibe descuento.

### 4.4 Interacción con las exenciones propias de Rentas

- Una **solicitud local** de exención (ciudadano → personal → supervisor) usa las HU propias, y sus eventos `exemptionRequested` / `updateExemptionStatus` salen desde M5 cuando corresponda.
- Un **beneficio social ya aprobado por M8** entra mediante `socialBenefitUpdated` y **no debe convertirse automáticamente en una solicitud local** que requiera otro acto de aprobación por cada beneficiario; lo que requiere aprobación del Supervisor es la **regla tributaria general** que habilita ese beneficio.
- No duplicar descuentos si el ciudadano ya tiene una exención local. Revisión de normativa/precedencia se puede ajustar después mediante configuración autorizada, pero para el TP la regla elegida es **máximo porcentaje sin acumulación**.

---

## 5. CONTRATOS SALIENTES M5 → M8 — ejemplos oficiales del PDF

**Regla de publicación:** todos los mensajes de M5 usan el mismo envelope de Core (`sourceModule: "rentas"`). Publicar eventos **cuando ocurra el hecho de negocio**, no por el mero hecho de abrir una pantalla, emitir una boleta o consultar el saldo.

### 5.1 `overdueDebt` — deuda vencida (pág. 7)

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

**Reglas M5:** emitir para una deuda efectivamente vencida con saldo positivo; `outstandingAmount` corresponde al saldo actual; evitar reemitir reiteradamente el mismo hecho sin cambio relevante; no publicar un vencimiento para deudas canceladas o sin identificación del ciudadano requerida por este contrato. Fechas `YYYY-MM-DD`.

### 5.2 `debtSettled` — deuda cancelada / contrato unificado (págs. 7–8)

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

**Reglas M5:** este evento se publica solo cuando una deuda pasa efectivamente a saldo cero. `remainingBalance` y `outstandingBalance` deben coincidir (=0). `originType` y `originId` se conservan como en el contrato unificado con M4/M7; no crear un `debtSettledM8` ni variantes de nombre por consumidor. Para una multa de tránsito agregar `infractionId` solo si lo requiere el contrato unificado acordado y usar `TRAFFIC_FINE`. Para obligaciones comerciales usar `PERMIT_FEE` o `COMMERCIAL_FINE`. Evitar inventar `citizenId` cuando una obligación pertenece exclusivamente a una organización; procesar según la política de destinatarios/consumidores que ya acordaron. **El PDF de M8 ejemplifica deuda de ciudadano, no define otros supuestos.**

### 5.3 `exemptionRequested` — solicitud de exención de Rentas (pág. 8)

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

**Reglas M5:** publicar al registrar de forma efectiva una solicitud de exención **originada en Rentas**. Core la rutea a los consumidores suscriptos, que pueden incluir M1 y M8. No publicar este evento cuando M5 solo esté consumiendo `socialBenefitUpdated`.

### 5.4 `updateExemptionStatus` — solicitud resuelta por Rentas (pág. 9)

**Caso APPROVED — documentado por M8:**

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

**Caso REJECTED — acordado previamente por el equipo M5, NO ejemplificado en el PDF de M8:**

```json
{
  "eventId": "48e82935-4fc4-44ba-b432-bd3ac90be105",
  "eventType": "updateExemptionStatus",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-10T17:00:00-03:00",
  "sourceModule": "rentas",
  "correlationId": "53ffab11-34d9-49de-8a77-64d5f239d19e",
  "causationId": null,
  "data": {
    "requestId": "exr-00600",
    "citizenId": "c-00123",
    "status": "REJECTED",
    "conceptCode": "TASA_SERVICIOS",
    "reason": "No se acreditan los requisitos de la solicitud"
  }
}
```

**Reglas M5:** este evento representa la **resolución de una solicitud local de Rentas**. No duplicar emisiones según destinatario (M1 y M8 reciben mediante Core). No producir esta resolución en respuesta a un beneficio social M8 ya aprobado. `correlationId` preserva el hilo de la solicitud; `causationId` debe apuntar al evento previo si hubo uno que causó esta publicación, no inventar causalidad.

---

## 6. MODELO DE DATOS INTERNO RECOMENDADO (NO FORMA PARTE DE LOS EVENTOS)

Adaptar a las entidades existentes; evitar tablas duplicadas si el repositorio ya tiene equivalentes.

### 6.1 Beneficio social proyectado en M5

Campos lógicos:

- `externalBenefitId` (string; único con `sourceModule`), `citizenId` (string, M1), `applicationId`, `programId`, `programName`.
- `benefitTypes` (colección o JSON conforme a tecnología existente); no convertir asistencias alimentarias en exenciones monetarias.
- `status` enum: APPROVED, REJECTED, SUSPENDED, FINALIZED.
- `reason` nullable; `startDate`, `endDate` nullable; `sourceUpdatedAt` offset datetime.
- `sourceModule = desarrollo-social`, `lastSourceEventId`, `lastSourceCorrelationId`; `createdAt`, `updatedAt` internos para auditoría.
- `mappingStatus`/`resolutionStatus` derivado o persistido: RESOLVED, PENDING_TAX_RULE, PENDING_CITIZEN, CONFLICT (según arquitectura).

### 6.2 Regla de programa → tributo

Campos lógicos:

- `ruleId`, `programId`, `benefitType = TAX_EXEMPTION`, `conceptCode`, `percentage` (0..100).
- `effectiveFrom`, `effectiveUntil`, `enabled`, `version`.
- `approvalStatus` (DRAFT/PENDING/APPROVED/REJECTED o enums existentes), `proposedBy`, `approvedBy`, fechas, motivo de cambio.
- Restricciones para evitar reglas activas contradictorias para la misma combinación de programa, concepto y vigencia.

### 6.3 Auditoría e idempotencia

- Inbox de `processedEventId` (único) + resultado y fecha, transaccional con el cambio de negocio.
- Historial o trazabilidad de cambios de beneficio por `externalBenefitId`, status anterior/nuevo, fechas, reason, `eventId`.
- Registros de liquidación guardando `grossAmount`, `exemptionPercentage`, `exemptAmount`, `netAmount`, `sourceBenefitId`, `appliedRuleVersion` si hubo beneficio.
- Outbox/publicación confiable para eventos salientes cuando el proyecto ya lo utilice, sin perder eventos tras confirmar la transacción de BD.

### 6.4 Seguridad

- Personal Rentas: propone/configura reglas según las HUs vigentes.
- Supervisor: autoriza nueva versión y activación de regla; jamás confiar en restricciones solo del frontend.
- Contribuyente: ve solo sus datos, sus exenciones efectivas y los descuentos en sus liquidaciones; no modifica reglas generales.
- Auditor: ve historial, sin permisos de edición.

---

## 7. ALGORITMOS REFERENCIA (PSEUDOCÓDIGO, ADAPTAR AL REPOSITORIO)

### 7.1 Consumidor de M8

```text
onMessage(envelope):
  validateCoreEnvelope(envelope)
  require eventType == "socialBenefitUpdated"
  require sourceModule == "desarrollo-social"
  require supported(eventVersion)
  if processed(eventId): ACK; return

  d = parseSocialBenefitUpdatedData(envelope.data)
  validateRequiredIdentifiersAndStatus(d)

  beginTransaction()
    reserveProcessedEventId(eventId)      # restricción UNIQUE, evitar carrera
    current = findSocialBenefitForUpdate("desarrollo-social", d.benefitId)
    if current != null and d.updatedAt < current.sourceUpdatedAt:
      appendAudit("STALE_EVENT", d, eventId)
    else if current != null and d.updatedAt == current.sourceUpdatedAt
            and businessContentDifferent(d, current):
      appendAudit("SAME_TIMESTAMP_CONFLICT", d, eventId)
      markForReview(current)
    else:
      upsertSocialBenefitProjection(d)
      if hasBenefitType(d, "TAX_EXEMPTION"):
        resolveCitizenOrMarkPending(d.citizenId)
        resolveApprovedTaxRuleOrMarkPending(d.programId)
      # Nunca generar liquidaciones ni solicitudes de exención aquí.
    markProcessed(eventId)
  commit()
  ACK
```

**Errores:** si falla BD o broker de forma transitoria: `nack(requeue=false)` según Core; un error de datos que nunca se resolverá con reintento no debe iniciar un bucle infinito: registrar/aislar conforme a integración acordada. Si falta `programId` mapeado, eso **NO** es un error técnico: guardar pendiente y ACK.

### 7.2 Liquidación (efecto tributario)

```text
calculateLiquidation(citizenId, conceptCode, taxableDate, grossAmount):
  approvedSocialBenefits = findBenefits(
    citizenId, status="APPROVED", type="TAX_EXEMPTION")
  candidatePercentages = []
  for benefit in approvedSocialBenefits:
    if dateWithinInclusiveRange(taxableDate, benefit.startDate, benefit.endDate):
      rule = approvedEnabledRule(benefit.programId, conceptCode, taxableDate)
      if rule exists: candidatePercentages.add(rule.percentage with provenance)

  candidatePercentages += approvedLocalExemptions(citizenId, conceptCode, taxableDate)
  applied = max(candidatePercentages, default=0)
  discount = moneyRound(grossAmount * applied / 100)
  net = max(0, grossAmount - discount)
  persistImmutableLiquidationSnapshot(grossAmount, applied, discount, net, references)
  if net > 0: createCollectibleDebt(net)
  return liquidation
```

**Importantísimo:** no combinar montos o porcentajes de `FOOD_ASSISTANCE` / `UTILITY_SUBSIDY` con `TAX_EXEMPTION`.

---

## 8. CASOS DE PRUEBA OBLIGATORIOS (AUTOMATIZADOS + MANUALES)

| ID | Preparación y acción | Resultado esperado |
|---|---|---|
| M8-01 | Recibir `APPROVED` con `TAX_EXEMPTION`, beneficiario local y regla aprobada 100 % para `TASA_SERVICIOS`. | Beneficio registrado; próxima liquidación elegible $80.000 bruto → $80.000 exento → $0 neto. |
| M8-02 | Mismo beneficiario; liquidar tributo distinto. | No hay descuento por la regla de `TASA_SERVICIOS`. |
| M8-03 | Recibir programa `prog-otro` sin regla aprobada. | Beneficio pendiente de parametrización; ninguna exención inventada. |
| M8-04 | Dos reglas/beneficios efectivos de 40 % y 60 % sobre mismo tributo, incluido uno local. | Se aplica **60 %**, no 100 %. |
| M8-05 | Recibir `REJECTED`. | Registrar rechazo y motivo; no descontar en nuevas liquidaciones. |
| M8-06 | Recibir `APPROVED`, luego `SUSPENDED`; nueva liquidación. | No aplica descuento mientras está suspendido. |
| M8-07 | Recibir `APPROVED`, luego `FINALIZED`; nueva liquidación. | No aplica descuento. Se conserva historial y endDate si existe. |
| M8-08 | Reenviar un evento con **mismo `eventId`**. | Se procesa una sola vez, sin beneficios ni descuentos duplicados. |
| M8-09 | Llega `SUSPENDED` más reciente; luego `APPROVED` anterior por reintento. | No reactivar beneficio; conservar estado más reciente. |
| M8-10 | Dos mensajes del mismo `benefitId` con mismo `updatedAt` y diferente estado. | Registrar conflicto; no sobrescribir silenciosamente. |
| M8-11 | `APPROVED` con `FOOD_ASSISTANCE` + `UTILITY_SUBSIDY` pero sin `TAX_EXEMPTION`. | No alterar liquidaciones ni deuda; no usar importes asistenciales como exención. |
| M8-12 | Ciudadano de M8 aún no identificado en M5. | Registrar pendiente; no imputar a persona equivocada; resolver después. |
| M8-13 | Emitir liquidación de $80.000 antes de aprobar beneficio; recibir aprobación luego. | Liquidación histórica no se modifica; ajuste manual controlado si corresponde. |
| M8-14 | Un empleado intenta crear/activar regla de programa sin aprobación supervisora. | Operación prohibida; regla no tiene efecto. |
| M8-15 | Supervisor aprueba regla; empleado liquida; ciudadano abre su portal. | Supervisor ve aprobación, Rentas ve descuento y ciudadano ve importe original/descuento/neto. |
| M8-16 | Publicar `overdueDebt` sobre deuda vencida por $85.000. | Envelope Core + `data` exactos; M8 puede consumir; solo un hecho lógico. |
| M8-17 | Abonar deuda por completo; publicar `debtSettled`. | `remainingBalance=0`, `outstandingBalance=0`, `originType`/`originId` correctos; no sale con pagos parciales. |
| M8-18 | Crear solicitud de exención local desde M5. | Emitir una sola vez `exemptionRequested`; M1/M8 reciben por suscripción, no copias manuales. |
| M8-19 | Supervisor aprueba/rechaza solicitud local. | Emitir `updateExemptionStatus` según caso, con trazabilidad y sin activación anticipada. |
| M8-20 | Recibir `socialBenefitUpdated` desde M8. | **No emitir** `exemptionRequested` ni `updateExemptionStatus` como eco automático. |
| M8-21 | Evento `APPROVED` con vigencia futura. | Antes de `startDate` no se aplica; dentro de vigencia sí; después de `endDate` no. |
| M8-22 | Usuarios ciudadano A/B y auditor; intentar ver beneficios de A usando B. | B no puede consultar beneficios de A; auditor solo lectura. |
| M8-23 | Simular caída transitoria de persistencia. | Reintento administrado por Core, sin duplicar efectos al recuperarse. |
| M8-24 | Evento M8 `socialBenefitUpdated` con `reason` null, con reason string y `startDate` null en suspendido. | Deserialización/validación robustas; no excepciones por nulabilidad esperada. |

**Pruebas unitarias e integración:** cubrir parser/DTO del envelope, validaciones condicionales, transición de estados, idempotencia, actualización fuera de orden, configuración de reglas, cálculo de exención, permisos y persistencia. Si existen pruebas de contratos/schemas para Core y M8, reutilizarlas. No necesitar un servidor M8 real para las unitarias: usar JSON de ejemplo y dobles del broker; integrar con Core en un ambiente separado cuando esté disponible.

### Recorrido multirrol recomendado

1. **Supervisor de Rentas** aprueba la regla `prog-00789` → `TASA_SERVICIOS` → 100 %.
2. **M8** publica `socialBenefitUpdated/APPROVED` para el ciudadano A.
3. **Personal de Rentas** consulta al ciudadano, verifica beneficio social vigente y genera una nueva liquidación de `TASA_SERVICIOS` por $80.000 bruto.
4. **Ciudadano A** abre el portal y comprueba bruto $80.000, descuento $80.000, neto $0. **Ciudadano B** no debe verla.
5. **M8** publica `SUSPENDED` con `updatedAt` posterior.
6. **Personal de Rentas** genera una nueva liquidación posterior con base $80.000: el descuento social ya no se aplica.
7. **Auditor** comprueba historial de ambos eventos y qué regla/estado se aplicó a cada liquidación sin modificar sus importes históricos.

---

## 9. INSTRUCCIONES DE IMPLEMENTACIÓN PARA CODEX

### Objetivo

**Revisar y modificar el repositorio de M5** para que la integración M8 ↔ M5 quede funcional con Core, aplicando las decisiones de negocio descritas. No detenerse en un diagnóstico únicamente: implementar los cambios que sean seguros y estén dentro del repositorio.

### Procedimiento

1. **Inspeccionar antes de cambiar:** identificar arquitectura actual (lenguaje, framework, módulos, DTOs, servicios, entidades, migraciones, eventos, auth y tests); buscar código ya existente de `socialBenefitUpdated`, `overdueDebt`, `debtSettled`, `exemptionRequested`, `updateExemptionStatus` y exenciones tributarias.
2. **Comparar contrato:** revisar nombre, envelope, `sourceModule`, payload, tipos, nulabilidad, `APPROVED/REJECTED/SUSPENDED/FINALIZED`, `reason`, fecha, idempotencia y orden de actualización. Anotar hallazgos.
3. **Implementar consumidor entrante M8** y sus validaciones sin imponer `UUID` a `citizenId`, `programId` ni `benefitId` si son strings del contrato.
4. **Persistir proyección e historial** de beneficios M8 en M5 y construir regla local programática para `TAX_EXEMPTION`, con propuesta/aprobación del Supervisor.
5. **Conectar regla al motor de liquidaciones** en un único punto de aplicación; evitar recálculo de liquidaciones históricas; manejar `gross`, `discount`, `net`, `benefitId` y versión de regla.
6. **Verificar/enmendar publicaciones existentes M5 → M8** con `data` exacto: `overdueDebt`, `debtSettled`, `exemptionRequested`, `updateExemptionStatus` y envelope Core; evitar múltiples publicaciones por suscriptor. Mantener contrato unificado de `debtSettled` ya compartido con M4/M7.
7. **Verificar UX:** permitir a Personal de Rentas revisar beneficios, pendientes, reglas aprobadas; a Supervisor aprobar reglas; a ciudadano ver el descuento en sus propias liquidaciones; a auditor ver historial sin modificar nada. Integrar en las pantallas existentes, no inventar páginas duplicadas innecesariamente.
8. **Implementar y ejecutar pruebas** listadas en §8 según entorno. Si el proyecto ya tiene suites de pruebas, seguir el estilo del repositorio.
9. **Migraciones seguras:** si faltan tablas/columnas, agregar migraciones incrementales, restricciones únicas y valores por defecto compatibles. No borrar ni sobrescribir datos existentes.
10. **Entregar reporte** con archivos cambiados, contratos verificados, pruebas ejecutadas, resultado, puntos bloqueados por información externa y comandos necesarios para levantarlo.

### Restricciones

- No modificar repositorios externos ni proponer que M8 cambie los payloads; los datos `conceptCode` y `percentage` **nacen de las reglas M5**.
- No publicar nuevos `eventType`, no renombrar los existentes, no exponer datos internos en el envelope.
- No romper M1/M2/M4/M7 ni sus eventos compartidos.
- No agregar un segundo pipeline tributario si ya existe un motor de liquidación; centralizar la aplicación del beneficio.
- No habilitar reglas tributarias sin aprobación del Supervisor.
- No ajustar liquidaciones ya emitidas sin flujo de ajuste explícito/autorizado.
- No inventar campos en el JSON de M8, endpoints de M8 ni datos simulados de titularidad en producción.
- Si una decisión requiere información no presente (por ejemplo, cuál es la fecha de devengamiento existente), reutilizar reglas del repositorio, documentar el supuesto y pedir aclaración **solo si bloquea una implementación correcta**.
- No inventar resultados de pruebas ni afirmar integraciones en vivo si Core/M8 no están accesibles.

### Entrega esperada de Codex

```text
A) Resumen de hallazgos previos
B) Cambios aplicados, ruta de cada archivo y explicación
C) Migraciones y tablas/DTOs/servicios modificados
D) Ejemplos reales de JSON serializado contra los contratos del PDF
E) Pruebas ejecutadas (comando + resultado)
F) Pruebas manuales con distintos roles y pasos reproducibles
G) Pendientes externos o supuestos residuales
```

---

## 10. Mapa de fuentes y límites

**Documento oficial proporcionado por M8:** `EventosDesarrolloSocialConEnvelope.pdf`.

- **Págs. 2–4:** `socialBenefitUpdated`, campos y estados.
- **Págs. 7–8:** `overdueDebt` y `debtSettled` de M5.
- **Págs. 8–9:** `exemptionRequested` y `updateExemptionStatus` de M5.
- **Págs. 1, 5–6 y 10–11:** eventos de M8/M1/M2 que **no** son una integración directa M8 → M5 para beneficios tributarios.

**El PDF no establece**: mapeo `programId` → tributo, porcentaje de exención de TAX_EXEMPTION, prioridad entre exenciones, qué hacer con reglas faltantes, ni cómo modificar liquidaciones históricas. Esas reglas quedaron **definidas expresamente por M5 en este documento**, conforme al acuerdo de negocio mantenido con el equipo. No atribuirlas a M8.

**Definición de listo:** el código de M5 consume `socialBenefitUpdated` con el envelope Core, registra correctamente sus cuatro estados sin desorden/duplicados, aplica a nuevas liquidaciones solamente una regla tributaria autorizada y vigente, no reescribe históricos y publica los cuatro eventos hacia M8 conforme a los JSON acordados. Las pruebas pertinentes pasan y el informe de Codex enumera cualquier limitación de integración real.
