# Propuesta de corrección de identificadores — M5 Rentas ↔ M8 Desarrollo Social

**Fecha:** 20/10/2026
**Estado:** propuesta para aceptación conjunta por M5 y M8; **NO** es una nueva versión ya ratificada del documento de M8.
**Base documental:** `EventosDesarrolloSocialConEnvelope.pdf`, 11 páginas (M8); comunicado de Core y contratos previamente acordados.
**Propósito:** alinear identificadores de entidades a UUID sin alterar nombres de eventos, nombres de campos, reglas económicas ni contratos de M4/M7.

## 1. Regla transversal de identificación

- **Todos los identificadores de entidades que cruzan módulos en estos eventos son cadenas UUID** (`format: uuid` en JSON Schema). No confundir con códigos de negocio ni documentos personales.
- **M1 es la fuente de verdad del identificador de ciudadano**: `citizenId` debe ser el mismo UUID canónico en M1, M2, M5 y M8. No derivar `citizenId` de DNI/CUIL/CUIT ni generar otro UUID local para la misma persona.
- **M5 es dueño de** `debtId`, `requestId` (solicitudes iniciadas por M5), `exemptionId` (exenciones propias de M5) y los IDs de sus liquidaciones tributarias. Asigna una sola identidad UUID estable por entidad, y publica la misma cada vez.
- **M8 es dueño de** `benefitId`, `applicationId`, `programId`: deben ser UUID estables asignados por M8 y reutilizados sin regenerarlos en cada actualización.
- `originId` es **el identificador estable de la entidad de origen**, no uno inventado al publicar. Para `MUNICIPAL_TAX`, identificar por UUID de liquidación de M5; para `TRAFFIC_FINE`, por el UUID `infractionId` de M7. **Para `PERMIT_FEE` y `COMMERCIAL_FINE`, coordinar con M4** porque su contrato anterior emplea `FEE-001`/`FINE-001`: no cambiar el valor recibido ni el que necesita M4 hasta que haya correspondencia aprobada.
- `citizenId` puede ser `null` cuando el concepto corresponda a una persona jurídica sin ciudadano titular. No inventar un ciudadano para completar el campo; M8 debe acordar qué casos procesa y cuáles ignora.
- Se mantienen como texto/código, **no UUID**: `eventType`, `sourceModule`, `conceptCode`, `originType`, `status`, `programName`, `reason`, `benefits[].type`, DNI/CUIL/CUIT, patentes y fechas. `correlationId` y `causationId` ya tienen formato UUID o null en el envelope.
- Los UUID de los ejemplos de este documento son **datos ficticios de prueba**, no IDs verdaderos de M1 ni de M8.

## 2. Envelope oficial de Core (sin modificaciones)

```json
{
  "eventId": "9075872f-5b3e-4637-925e-bd38dcca8b15",
  "eventType": "socialBenefitUpdated",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-20T10:00:00-03:00",
  "sourceModule": "desarrollo-social",
  "correlationId": "360841d9-2eec-4506-b55c-69c3d56b5aa0",
  "causationId": null,
  "data": {}
}
```

- Sólo se admiten los ocho campos del envelope; cualquier información propia va en `data`.
- `eventId` nuevo UUIDv4 por hecho; reutilizarlo al reintentar **el mismo hecho**; cada consumidor debe deduplicar.
- Si un evento nace de otro consumido, preservar su `correlationId` y poner el `eventId` causal como `causationId`; si no, iniciar correlación según Core.
- Todos los eventos M5→M8 llevan `sourceModule: "rentas"`; M8→M5 llevan `sourceModule: "desarrollo-social"`.
- El versionado de `eventVersion` se acuerda con Core/M8: si estos schemas aún son WIP y no están desplegados, pueden ratificarse bajo `"1.0"`; si ya hay consumidores con formato anterior en funcionamiento, imponer UUID donde antes se admitían otros strings es un cambio incompatible y requiere transición/versionado coordinados. No cambiar versión de forma unilateral.

## 3. M8 → M5: socialBenefitUpdated

**Es el único evento propio de M8 que consume Rentas.** M5 filtra beneficios que incluyan `TAX_EXEMPTION` y registra/actualiza su proyección; otros beneficios (`FOOD_ASSISTANCE`, `UTILITY_SUBSIDY`) no modifican tributos.

```json
{
  "eventId": "9075872f-5b3e-4637-925e-bd38dcca8b15",
  "eventType": "socialBenefitUpdated",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-20T10:00:00-03:00",
  "sourceModule": "desarrollo-social",
  "correlationId": "360841d9-2eec-4506-b55c-69c3d56b5aa0",
  "causationId": null,
  "data": {
    "benefitId": "7641336c-a242-476d-bb77-79367112d700",
    "citizenId": "2808fa6c-1e50-4f71-a46e-ca444458924b",
    "applicationId": "be7bcc6a-3c0e-4ecd-8562-92b3f7d3d524",
    "programId": "d740f546-d595-4927-b477-d847b4cb3f31",
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
    "updatedAt": "2026-10-20T10:00:00-03:00"
  }
}
```

**Estados admitidos según M8:** `APPROVED`, `REJECTED`, `SUSPENDED`, `FINALIZED`. Los cuatro usan los **mismos UUID de `benefitId`, `applicationId`, `programId` y `citizenId`** cuando corresponden a la misma entidad; `status`, `reason`, `startDate` y `endDate` varían según el estado documentado. Nunca generar otro `benefitId` para actualizar el mismo beneficio.

## 4. M5 → M8: overdueDebt

```json
{
  "eventId": "5c23c27e-8c71-4ea3-9c21-e196ce8ba538",
  "eventType": "overdueDebt",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-20T10:15:00-03:00",
  "sourceModule": "rentas",
  "correlationId": "c1b22929-5d7a-4ca4-90a9-c2f69403b9b7",
  "causationId": null,
  "data": {
    "debtId": "653d9e79-2cd1-4ec1-8ada-845374f6903d",
    "citizenId": "2808fa6c-1e50-4f71-a46e-ca444458924b",
    "conceptCode": "TASA_SERVICIOS",
    "outstandingAmount": 85000,
    "dueDate": "2026-09-20"
  }
}
```

`debtId` se genera/define en M5 y se conserva. `citizenId` es la referencia canónica de M1. `conceptCode` no se transforma.

## 5. M5 → M8: debtSettled (evento COMPARTIDO con M4 y M7)

**Restricción esencial:** mantener exactamente los nombres y la semántica del payload unificado ya acordado, así como un publisher común para todos los consumidores. El ejemplo representa una deuda tributaria propia de Rentas, `originType=MUNICIPAL_TAX`.

```json
{
  "eventId": "2a4fd82a-6be0-4b14-bb18-c25df8b60478",
  "eventType": "debtSettled",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-20T11:00:00-03:00",
  "sourceModule": "rentas",
  "correlationId": "c1b22929-5d7a-4ca4-90a9-c2f69403b9b7",
  "causationId": null,
  "data": {
    "debtId": "653d9e79-2cd1-4ec1-8ada-845374f6903d",
    "citizenId": "2808fa6c-1e50-4f71-a46e-ca444458924b",
    "conceptCode": "TASA_SERVICIOS",
    "originType": "MUNICIPAL_TAX",
    "originId": "d53f7338-269c-428f-89a5-03eda40a9be7",
    "settledAt": "2026-10-20T11:00:00-03:00",
    "remainingBalance": 0,
    "outstandingBalance": 0
  }
}
```

- Para una multa de M7, conservar el contrato compartido (`originType=TRAFFIC_FINE`, `originId=infractionId` UUID y campo adicional `infractionId`).
- Para habilitaciones de M4, conservar la identidad real de `originId` que M4 espera hasta resolver el esquema UUID con ellos. **No reemplazar `FEE-001` por un UUID inventado**; eso impediría que M4 encuentre la tasa original.
- `citizenId` es UUID de M1 o null si corresponde una organización sin ciudadano identificable, sujeto al filtrado acordado con M8. No usar DNI como `citizenId`.
- Aunque M8 se suscriba al `eventType` y reciba hechos de diferentes `originType`, deberá procesar sólo los que corresponden a su dominio y confirmar/ignorar los demás.

## 6. M5 → M8: exemptionRequested

```json
{
  "eventId": "8678147f-4943-4eb4-8b11-82fd7054626d",
  "eventType": "exemptionRequested",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-20T12:00:00-03:00",
  "sourceModule": "rentas",
  "correlationId": "657ca129-e631-4aa8-9061-42dc24978718",
  "causationId": null,
  "data": {
    "requestId": "e5dd27f4-c803-4d5c-a75e-c003d99cc5af",
    "citizenId": "2808fa6c-1e50-4f71-a46e-ca444458924b",
    "conceptCode": "TASA_SERVICIOS",
    "reason": "Situación socioeconómica",
    "requestedPercentage": 100,
    "requestedFrom": "2026-11-01",
    "requestedUntil": "2027-08-31"
  }
}
```

El `requestId` identifica **esa solicitud específica**; no confundirlo con `applicationId` de una solicitud social de M8.

## 7. M5 → M8: updateExemptionStatus

Ejemplo `APPROVED`:

```json
{
  "eventId": "bf04d7bc-0e09-46d1-9568-00a421a8f33d",
  "eventType": "updateExemptionStatus",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-20T13:00:00-03:00",
  "sourceModule": "rentas",
  "correlationId": "657ca129-e631-4aa8-9061-42dc24978718",
  "causationId": null,
  "data": {
    "requestId": "e5dd27f4-c803-4d5c-a75e-c003d99cc5af",
    "citizenId": "2808fa6c-1e50-4f71-a46e-ca444458924b",
    "status": "APPROVED",
    "exemptionId": "f0c9ec39-d07e-46cf-b379-eb7f57488fa5",
    "conceptCode": "TASA_SERVICIOS",
    "percentage": 100,
    "validFrom": "2026-11-01",
    "validUntil": "2027-08-31"
  }
}
```

- `requestId` debe ser **exactamente el mismo UUID de `exemptionRequested`**.
- `exemptionId` es el UUID de la exención otorgada por M5; diferente de `requestId` y diferente de `benefitId` de M8.
- Para `REJECTED`, se conserva el payload ya acordado para el rechazo: `requestId` UUID, `citizenId` UUID, `status=REJECTED`, `conceptCode` y `reason`; no inventar `exemptionId` cuando no se otorgó exención.

## 8. Regla funcional de exenciones provenientes de M8 (sin cambio de contrato)

1. M8 publica `socialBenefitUpdated` con `status=APPROVED` y un elemento `benefits[].type=TAX_EXEMPTION`.
2. M5 proyecta el beneficio usando (`benefitId`, `citizenId`, `programId`) recibidos; deduplica por `eventId` y no pisa estados posteriores con eventos viejos.
3. M5 consulta su **parametrización tributaria propia** por UUID de `programId`. Esa regla establece `conceptCode`, porcentaje, vigencia aplicable y habilitación; su activación requiere las autorizaciones propias de M5.
4. Cuando liquida un período comprendido en la vigencia, M5 aplica la exención indicada por la regla (si no existe regla, registra pendiente de parametrización **sin aplicar un descuento inventado**).
5. El caso de prueba anterior `prog-00789 → TASA_SERVICIOS → 100%` debe reemplazarse por la misma asociación configurada con el **UUID real del programa**, no por un programa UUID nuevo y no reconocido.
6. `REJECTED` no activa beneficio; `SUSPENDED` detiene la aplicación para nuevas liquidaciones mientras dure; `FINALIZED` finaliza la aplicación futura. Conservar historia; no recalcular automáticamente obligaciones ya emitidas.
7. Si coinciden dos exenciones del mismo tributo, se usa la de mayor porcentaje, sin sumarlas, según la regla de negocio definida para M5.

## 9. Referencias de M8 con otros módulos (fuera de los cinco eventos bilaterales)

Para que la corrección sea realmente transversal, **M8 también debe revisar sus propios eventos hacia otros módulos**, especialmente:

- `socialDocumentationRequested` (M8→M1): `citizenId`, `applicationId`, `programId` deben tener UUID reales.
- `updateTicketStatus` (M8→M2): `ticketId` UUID y `updatedBy.id` UUID o null según M2 v1.78; para actor humano usar UUID real y `EXTERNAL_USER`; proceso automático usa `SYSTEM`/null.
- `ticketUpdated` (M2→M8): `ticketId` y `citizenId` ya están documentados como UUID por M2; **el valor de `citizenId` debe coincidir con M1**.
- Los mensajes provenientes de M1 deben usar el identificador canónico y no IDs numéricos heredados cuando representen el mismo `citizenId` de integración.

Estos eventos NO deben añadirse como consumidores de M5: se señalan sólo para evitar que M8 conserve modelos de IDs incompatibles en otras integraciones.

## 10. Implementación y migración sin romper datos

1. Auditar tipos reales de claves internas y externas en M5 y M8; identificar referencias ya persistidas con prefijos (`c-00123`, `ben-00789`, `debt-03200`, etc.).
2. Obtener de **M1** la correspondencia oficial entre ciudadano heredado y UUID canónico. No crear UUID independiente para un ciudadano conocido.
3. Cada módulo dueño asigna/migra **una vez** UUID persistente a sus propias entidades (deuda M5, solicitud M5, exención M5; beneficio M8, aplicación M8, programa M8). Guardar mapeos para trazabilidad y migrar relaciones locales sin perder historia.
4. Mantener nombres de campos y eventos. Modificar validaciones/DTOs, serialización, lookups, filtros y datos de prueba para usar UUID en los campos indicados. No hacer reemplazos superficiales en ejemplos sin asegurar identidad persistente.
5. Revisar `programId` en las reglas de exención configuradas, para que apunten al programa UUID real de M8.
6. Revisar `originId` y compartir evaluación con M4/M7 **antes** de alterar cualquier identificador que esos módulos usen para reconocer una obligación.
7. Validar y registrar los schemas definitivos con Core según el acuerdo de versión; no cambiar `eventVersion` ni la forma de `debtSettled` para todos los consumidores de manera unilateral.
8. Procesar mensajes de manera idempotente y tolerante a desorden; un UUID mal formado debe generar rechazo controlado, no un mapeo silencioso a otra persona.

## 11. Pruebas mínimas de aceptación

- M1, M2, M5, M8: un ciudadano de prueba conserva **exactamente el mismo UUID** en los eventos intercambiados.
- Beneficio `APPROVED` de M8: M5 localiza al ciudadano por UUID M1, el programa por UUID M8 y aplica la regla correcta al liquidar.
- `SUSPENDED`/`FINALIZED`: no reactivan beneficios por eventos `APPROVED` antiguos; no recalculan deudas históricas sin procedimiento de ajuste.
- `overdueDebt`: dos envíos del mismo hecho no crean duplicados en M8.
- `debtSettled`: `debtId` UUID es igual al de `overdueDebt`; M8 recibe saldo cero, y M4/M7 conservan la estructura y referencias esperadas.
- `exemptionRequested` seguido de `updateExemptionStatus`: `requestId` UUID idéntico, `citizenId` UUID idéntico; `exemptionId` UUID diferente al de la solicitud y sólo cuando la exención existe.
- Identificadores con formato anterior (`c-00123`, `debt-03200`) no pasan la validación de los **nuevos** schemas UUID, salvo migración/compatibilidad expresamente pactada.
- La misma actualización `socialBenefitUpdated` repetida con idéntico `eventId` no duplica el beneficio.
- Caso de persona jurídica sin `citizenId`: no se asigna uno ficticio; el contrato y filtro de M8 deben definir si ignora el evento o lo procesa por otra identidad.
- Pruebas de regresión de `paymentRegistered`, `debtSettled`, `paymentReversed` para M4 y M7: sin eliminación ni cambio de semántica de campos compartidos.

## 12. Aprobaciones necesarias antes de declarar el contrato cerrado

1. **M1:** confirma UUID canónico de ciudadano y forma de resolver registros previos.
2. **M8:** acepta formalmente los tipos `format: uuid` de sus IDs propios y de los recibidos de M5; confirma la misma identidad en todos los mensajes.
3. **M4/M7 (si corresponde):** acuerdan cualquier ajuste de `originId` y `citizenId` en `debtSettled` compartido; no cambiar sus referencias sin aprobación.
4. **Core:** valida registro de schemas y versión definitiva.

**No dar por finalizada la interoperabilidad de IDs sólo porque los JSON de ejemplo ya contienen UUID. Deben representar las mismas entidades de extremo a extremo.**
