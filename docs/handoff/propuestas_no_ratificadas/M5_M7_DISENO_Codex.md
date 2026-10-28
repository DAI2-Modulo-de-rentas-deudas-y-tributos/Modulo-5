# M5 Rentas ↔ M7 Tránsito — Contrato Core V4 e instrucciones de implementación

**Fecha:** 20/10/2026
**Para:** Codex, trabajando en el repositorio de M5 Rentas.
**Fuente externa:** `TPO-  EventosM7-V1.pdf` — M7 Tránsito, «Payloads actualizados – Core V4», vigente desde el **19/10/2026**, 10 páginas.
**Objetivo:** revisar el código existente de M5 y **aplicar** los cambios necesarios para consumir `infractionConfirmed` y publicar correctamente `paymentRegistered`, `debtSettled` y `paymentReversed`, con pruebas automáticas y funcionales.

> **Respuesta a la pregunta clave:** NO hay que solicitar que M7 modifique sus eventos. Su PDF ya contempla el envelope Core V4 y los cuatro contratos compartidos con Rentas. Esta tarea afecta **nuestro código M5**, solo cuando difiera de esos contratos. No editar M7 ni generar eventos nuevos.

## 0. Alcance y grado de certeza

- **[M7 / DOCUMENTADO]** `infractionConfirmed` (págs. 1–2); `paymentRegistered` (págs. 8–9); `debtSettled` y `paymentReversed` (págs. 9–10). Los nombres de los eventos y campos no se renombran.
- **[CORE / ACORDADO]** envelope `eventId`, `eventType`, `eventVersion`, `occurredAt`, `sourceModule`, `correlationId`, `causationId`, `data`; RabbitMQ 4.3; ruteo por `eventType`; entrega al menos una vez; idempotencia por `eventId`; **sin campos adicionales de primer nivel**.
- **[M5 / ACUERDO TRANSVERSAL]** `paymentRegistered`, `debtSettled` y `paymentReversed` son **un único contrato por tipo de evento**, compartidos por M4/M7 (y `debtSettled` también por M8). `originType`/`originId` determinan el tipo y procedencia de la deuda. `infractionId` se agrega cuando `originType == "TRAFFIC_FINE"`.
- **[M5 / DECISIÓN PROPUESTA DE IMPLEMENTACIÓN]** resolver DNI/CUIT a la entidad contribuyente local, registrar deudas pendientes de conciliación si faltan referencias; mantener ledger/auditoría, validaciones de negocio y entrega confiable; estas son reglas internas y **no constituyen campos adicionales exigidos por M7**.
- **[EJEMPLOS DE PRUEBA]** las fechas, importes de la secuencia y `citizenId: "c-00123"` son datos de fixture, no identificadores reales ni evidencia de una API adicional.

**No editar archivos/repositorios de M7, M4, M8, M1 o Core. No cambiar el payload de otros productores. No agregar endpoints REST entre módulos.** Inspeccionar antes qué mecanismos de eventos, servicio de pagos, origen de deudas, deduplicación y tests existen; reutilizarlos y mantener compatibilidad con el resto de Rentas.

## 1. Matriz M5 ↔ M7

| Dirección | `eventType` exacto | Obligación de M5 |
|---|---|---|
| **M7 → M5** | `infractionConfirmed` | **Consumir**; crear/vincular obligación `TRAFFIC_FINE` sin duplicación. |
| **M5 → M7** | `paymentRegistered` | **Publicar** después de aplicar un pago a una deuda de tránsito, incluidos pagos parciales. |
| **M5 → M7** | `debtSettled` | **Publicar** solamente cuando la deuda de tránsito quede totalmente cancelada. Mismo evento compartido con M4/M8. |
| **M5 → M7** | `paymentReversed` | **Publicar** después de ejecutar una reversión autorizada y actualizar saldos. |

Los otros eventos publicados por M7 (`streetClosure*`, `roadAccidentRegistered`) y los eventos que consume M7 de M2/M3/M6 **no pertenecen a esta integración**. No suscribir a M5 a ellos por este trabajo.

## 2. Envelope de Core — todos los mensajes externos

```json
{
  "eventId": "7f9c91f0-03c6-4baf-8791-8cb2c38981d2",
  "eventType": "infractionConfirmed",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-20T14:30:00-03:00",
  "sourceModule": "transito",
  "correlationId": "78cff4cc-3cd0-4f3f-b0f7-78ec2b15cf0b",
  "causationId": null,
  "data": {}
}
```

- `eventId`: UUIDv4 por **hecho/evento publicado**; si se reintenta publicar ese mismo hecho, **conservar exactamente el mismo** `eventId`.
- `eventType`: camelCase exacto, sin prefijos/sufijos.
- `eventVersion`: string `"1.0"` en la versión actual.
- `occurredAt`: doble **r**; timestamp ISO 8601 con offset o `Z`.
- `sourceModule`: `"transito"` al recibir de M7 y `"rentas"` al publicar desde M5. No `"M7"`/`"M5"` en el envelope.
- `correlationId`: conservar el hilo cuando un evento entrante causa la acción. Cuando la operación nace de una acción nueva de Rentas, generar uno nuevo; no asumir que todo pago posterior reutiliza el del alta original de multa.
- `causationId`: `eventId` del **evento recibido que causó directamente** el evento saliente, o `null` si nace de una acción directa; no inventar causalidad entre eventos independientes que se generan en una misma transacción.
- `data`: campos de negocio específicos del evento; **nunca** `payload` a nivel raíz.
- No agregar al envelope top-level `timestamp`, `producer`, `subject`, `specVersion`, `module` u otros campos.
- Los DTO de `data` deben respetar tipos (`BigDecimal`/decimal preciso para importes; strings para ID de negocio; UUID para `infractionId` y `eventId` según documento). No exigir UUID en `debtId` o `paymentId`, que son `string`.

## 3. M7 → M5: `infractionConfirmed` (contrato entrante confirmado)

**Ejemplo íntegro del documento de M7:**

```json
{
  "eventId": "7f9c91f0-03c6-4baf-8791-8cb2c38981d2",
  "eventType": "infractionConfirmed",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-20T14:30:00-03:00",
  "sourceModule": "transito",
  "correlationId": "78cff4cc-3cd0-4f3f-b0f7-78ec2b15cf0b",
  "causationId": null,
  "data": {
    "infractionId": "93fd4382-2343-4f3f-a127-9328b29fc520",
    "debtorId": "40123456",
    "debtorIdType": "DNI",
    "licensePlate": "AB123CD",
    "infractionType": "ILLEGAL_PARKING",
    "infractionDateTime": "2026-10-20T13:45:00-03:00",
    "baseAmount": 50000,
    "aggravatingFactors": ["REINCIDENCE"],
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

### Campos `data` requeridos según M7

| Campo | Tipo contractual | Tratamiento en M5 |
|---|---|---|
| `infractionId` | UUID | Clave de origen lógico; vincular con la obligación. |
| `debtorId` | string | DNI o CUIT según `debtorIdType`; **no** es `citizenId`. |
| `debtorIdType` | `"DNI"` o `"CUIT"` | Resolver al contribuyente local por el tipo correcto. |
| `licensePlate` | string | Conservar para consulta/trazabilidad. |
| `infractionType` | string/enum M7 | Conservar; no inventar enum cerrado sin documentar todos sus valores. |
| `infractionDateTime` | datetime ISO 8601 | Fecha/hora de la infracción, distinta de `occurredAt`. |
| `baseAmount` | number | Dato informativo del cálculo realizado por M7. |
| `aggravatingFactors` | array | Conservar; no recalcular multas desde agravantes. |
| `finalAmount` | number | **Monto de la obligación inicial**: $75.000 en el ejemplo. |
| `inspectorId` | string | Conservar referencia. |
| `location` | object | Conservar calle, intersección y coordenadas recibidas. |

**Procesamiento de negocio propuesto para M5:**

1. Consumir de la cola de Rentas, validar envelope (`sourceModule="transito"`, `eventType="infractionConfirmed"`) y payload obligatorio.
2. Deduplicar por `eventId`; adicionalmente impedir segunda deuda para la combinación lógica **(`originType="TRAFFIC_FINE"`, `originId=infractionId`)**, incluso si M7 reenviara la misma infracción con distinto `eventId`.
3. Resolver `debtorId` + `debtorIdType` con la proyección local de personas/organizaciones de M1. No confundir un DNI con `citizenId` interno; no atribuir una multa a otro contribuyente por coincidencia parcial.
4. Si el titular no está disponible todavía (eventos fuera de orden), persistir evento/obligación en estado de conciliación pendiente y volver a resolver al actualizar proyecciones; no perderla ni crear deuda visible en la cuenta equivocada.
5. Registrar obligación/deuda con `originType="TRAFFIC_FINE"`, `originId=infractionId`, concepto tributario de multa de tránsito según catálogo de M5, monto `finalAmount`, saldo `finalAmount`, y referencias del evento. Mantener trazabilidad y auditabilidad.
6. El documento de M7 **no envía `dueDate`, `citizenId` ni fecha de confirmación adicional**. El vencimiento es responsabilidad de reglas existentes M5; si no hay una regla, dejar pendiente definición o usar un estado apropiado, **no inventar un vencimiento contractual**.
7. Ack después del procesamiento persistido. Errores transitorios: nack con `requeue=false` según backoff Core. Mensaje válido no aplicable: ack/ignorar, no enviar a DLQ. Errores de datos permanentes: observar política de DLQ/rechazo sin reintentos infinitos.
8. Evitar efectos parcialmente confirmados usando transacciones y deduplicación persistente.

## 4. M5 → M7: publicaciones económicas — MISMO contrato compartido

**Regla de diseño obligatoria:** publicar **una sola instancia de evento por hecho relevante**, no una publicación separada "para M7" y otra "para M4". Core distribuye por `eventType` a los suscriptores; el consumidor de M7 solo debe aplicar los eventos `originType="TRAFFIC_FINE"`.

### 4.1 `paymentRegistered` — pago parcial (fixture contable coherente)

```json
{
  "eventId": "15e6e962-efb6-449a-99f0-68cba6f5ee1c",
  "eventType": "paymentRegistered",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-21T10:30:01-03:00",
  "sourceModule": "rentas",
  "correlationId": "877f098d-c484-4656-84fe-f48363eb3e19",
  "causationId": null,
  "data": {
    "paymentId": "PAY-09200",
    "debtId": "DEBT-03100",
    "originType": "TRAFFIC_FINE",
    "originId": "93fd4382-2343-4f3f-a127-9328b29fc520",
    "infractionId": "93fd4382-2343-4f3f-a127-9328b29fc520",
    "amountApplied": 50000,
    "paidAt": "2026-10-21T10:30:00-03:00",
    "remainingBalance": 25000
  }
}
```

**`data` requerido por M7:** `paymentId`, `debtId`, `originType`, `originId`, `infractionId`, `amountApplied`, `paidAt`, `remainingBalance`. `originType` **exactamente** `"TRAFFIC_FINE"`; `originId === infractionId` para multas de tránsito.

**Semántica M5:** registrar un pago por $50.000 aplicado a deuda de $75.000; nuevo saldo $25.000. Publicar luego de persistir pago/imputación; **no** publicar `debtSettled` porque todavía hay saldo. Para pago con imputaciones a varias deudas, emitir `paymentRegistered` **por cada imputación**, con `paymentId` posiblemente repetido pero `eventId` diferente; no asumir `paymentId` único por evento. Respetar reglas de cobro vigentes y no publicar pagos meramente iniciados o rechazados.

### 4.2 `debtSettled` — cancelación total

Para cerrar la misma deuda, el segundo pago aplica los $25.000 restantes, usando `paymentRegistered` con el **mismo esquema** anterior y nuevos valores (`paymentId="PAY-09201"`, `amountApplied=25000`, `remainingBalance=0`, `paidAt=2026-10-21T11:15:00-03:00`) y `eventId` nuevo. A continuación M5 genera **un** `debtSettled`:

```json
{
  "eventId": "22bbb8de-087a-4b36-834b-86cf16a7abcd",
  "eventType": "debtSettled",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-21T11:15:01-03:00",
  "sourceModule": "rentas",
  "correlationId": "4773d99b-9d0e-448b-a2a0-c53658265d29",
  "causationId": null,
  "data": {
    "debtId": "DEBT-03100",
    "citizenId": "c-00123",
    "conceptCode": "TRAFFIC_FINE",
    "originType": "TRAFFIC_FINE",
    "originId": "93fd4382-2343-4f3f-a127-9328b29fc520",
    "infractionId": "93fd4382-2343-4f3f-a127-9328b29fc520",
    "settledAt": "2026-10-21T11:15:00-03:00",
    "remainingBalance": 0,
    "outstandingBalance": 0
  }
}
```

**`data` requerido por M7:** `debtId`, `conceptCode`, `originType`, `originId`, `infractionId`, `settledAt`, `remainingBalance`, `outstandingBalance`; `citizenId` es **opcional** (`string` o `null`). Cuando M5 tenga una referencia canónica del ciudadano, incluirla; **no usar un DNI como sustituto automático de `citizenId`**. El documento de M7 usa `"40123456"` como `citizenId` en un ejemplo; eso parece un DNI y **no define por sí mismo la identidad canónica compartida**. Respetar el acuerdo resuelto por los equipos y el modelo M5.

**Semántica M5:** publicar solamente al transicionar el saldo a cero por cancelación efectiva, **una vez por transición**, no por renderizar una pantalla ni por pagar una cuota parcial. `remainingBalance = outstandingBalance = 0`. Preservar información en historial/auditoría. La misma publicación `debtSettled` debe ser utilizable por M4/M7/M8 según sus filtros, **sin multiplicar el evento por destinatario**. `conceptCode` debe corresponder al catálogo real de Rentas; `"TRAFFIC_FINE"` es el valor ejemplificado por M7.

### 4.3 `paymentReversed` — reversión efectivamente ejecutada

Para una secuencia contable **coherente**, supóngase que se revierte el **segundo pago de $25.000** cuando la deuda ya estaba saldada. El nuevo saldo correcto es $25.000:

```json
{
  "eventId": "e90552c4-9c59-4c74-bd9f-18c9ff2a4a5d",
  "eventType": "paymentReversed",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-21T12:00:01-03:00",
  "sourceModule": "rentas",
  "correlationId": "178d0cdc-3592-42fc-a14a-7b85e29b25ab",
  "causationId": null,
  "data": {
    "paymentId": "PAY-09201",
    "debtId": "DEBT-03100",
    "originType": "TRAFFIC_FINE",
    "originId": "93fd4382-2343-4f3f-a127-9328b29fc520",
    "infractionId": "93fd4382-2343-4f3f-a127-9328b29fc520",
    "reversedAmount": 25000,
    "reversedAt": "2026-10-21T12:00:00-03:00",
    "remainingBalance": 25000,
    "reason": "Reversión autorizada"
  }
}
```

**`data` requerido por M7:** `paymentId`, `debtId`, `originType`, `originId`, `infractionId`, `reversedAmount`, `reversedAt`, `remainingBalance`, `reason`. Se publica **después** de que la reversión fue aprobada y **ejecutada**, nunca al crear la solicitud de reversión. Actualizar la deuda y la trazabilidad antes de emitir. La deuda vuelve a estado con saldo; **NO** inventar `debtReopened` ni volver a publicar `infractionConfirmed`.

**Advertencia sobre el PDF de M7:** su ejemplo de `paymentRegistered` deja $25.000 pendientes y el ejemplo de `paymentReversed` revierte $50.000 pero deja saldo $50.000. Esa combinación **no constituye una secuencia contable coherente si no hubo otros movimientos**; no es razón para cambiar el contrato de M7. En M5, `remainingBalance` debe ser el saldo real tras ejecutar la reversión, no un número fijo copiado de ejemplos.

## 5. Compatibilidad M4/M7/M8 — no romper los otros contratos

| Evento | Publicación única M5 | M4 | M7 | M8 |
|---|---|---|---|---|
| `paymentRegistered` | Por imputación aplicada | Filtrar `PERMIT_FEE`/`COMMERCIAL_FINE` | Filtrar `TRAFFIC_FINE` | No está documentado como receptor |
| `debtSettled` | Por cancelación de deuda | Filtrar conceptos propios | Filtrar `TRAFFIC_FINE` | Consumidor para seguimiento de deudas |
| `paymentReversed` | Por reversión aplicada | Filtrar conceptos propios | Filtrar `TRAFFIC_FINE` | No está documentado como receptor |

- `originType` acordado: `PERMIT_FEE`, `COMMERCIAL_FINE`, `TRAFFIC_FINE`, `MUNICIPAL_TAX`.
- `originId` siempre identifica el objeto creado por el módulo de origen: FEE/FINE de M4, `infractionId` de M7, liquidación de M5 para tributos municipales.
- `infractionId` es requerido **cuando `originType="TRAFFIC_FINE"`**; para otro origen se puede omitir. No modificar el esquema de otros orígenes para que incluya un UUID inventado.
- `citizenId` se incluye en `debtSettled` cuando hay persona asociada y es aplicable al contrato; M7 lo declara opcional. No inventar `citizenId` de una organización.
- No emitir copia de los mismos hechos desde diferentes publishers por suscriptor. El ruteo lo hace Core.
- La existencia de varios suscriptores **no autoriza a cambiar el nombre ni el payload** según el destinatario.

## 6. Integración con Core y confiabilidad

1. Confirmar que M5 esté suscrito a `infractionConfirmed` (`POST /api/v1/subscriptions` con `eventType="infractionConfirmed"`, mediante el mecanismo de catálogo que ya use el repo). Confirmar publicaciones declaradas de `paymentRegistered`, `debtSettled` y `paymentReversed`.
2. Los eventos se publican por `muni.inbox`; M5 consume desde **`q.rentas`**, única cola compartida entre los tipos a los que M5 esté suscrito. El consumidor debe despachar por `eventType`. La cola la declara Core: al inspeccionarla usar declaración **pasiva** si corresponde; no redefinir argumentos incompatibles.
3. Control de duplicados persistente: `eventId` para entrega y origen lógico (`TRAFFIC_FINE`, `infractionId`) para deuda. `ack` únicamente cuando el procesamiento se guardó con éxito o el evento se ignoró por razón legítima.
4. Reintentos con `nack(requeue=false)` para fallos transitorios; dejar que Core administre backoff y DLQ. Nunca usar `requeue=true` indefinidamente.
5. Core no garantiza orden absoluto: si falta un contribuyente proveniente de M1, almacenar pendiente de vinculación y reconciliar; no rechazar para siempre una infracción legítima.
6. Evitar «pago aplicado en DB, evento nunca publicado»: usar outbox transaccional si el proyecto ya dispone de ella, o adecuar el mecanismo de entrega fiable existente sin reestructuraciones innecesarias. Retransmisión del mismo hecho conserva `eventId`.
7. Las autorizaciones de pagos/reversiones permanecen en M5; el broker transporta hechos de negocio confirmados, **no** solicitudes incompletas.
8. No hacer llamadas REST directas M5 ↔ M7; el diseño de integración es por eventos.

## 7. Casos de prueba obligatorios

### A. Consumidor M7 → M5

| ID | Caso | Resultado esperado |
|---|---|---|
| M7-01 | `infractionConfirmed` válido con DNI, `finalAmount=75000` | Una deuda `TRAFFIC_FINE` por $75.000, vinculada al contribuyente DNI correcto y al UUID de infracción. |
| M7-02 | Mismo `eventId` dos veces | Una sola deuda y una sola imputación lógica. |
| M7-03 | Nuevo `eventId` pero mismo `infractionId` | No crear una segunda deuda; diagnosticar repetición del mismo origen. |
| M7-04 | `debtorIdType="CUIT"` | Buscar contribuyente de organización por CUIT, sin usar identificación DNI. |
| M7-05 | Titular aún no proyectado desde M1 | Registro pendiente de conciliación, sin atribución falsa; resolver cuando estén los datos. |
| M7-06 | Evento válido sin `dueDate` | No fallar por campo inexistente; aplicar regla local de vencimiento o registrar pendiente si no existe. |
| M7-07 | Evento sin `finalAmount`, `infractionId` o tipo inválido | Validación/rechazo controlado según mecanismo de errores; no crear deuda corrupta. |
| M7-08 | Otros eventos `eventType` recibidos en `q.rentas` | Despachar solo a su handler; no confundir con infracciones. |

### B. Publishers M5 → M7

| ID | Caso | Resultado esperado |
|---|---|---|
| M7-09 | Cobro parcial de $50.000 sobre $75.000 | `paymentRegistered` con `remainingBalance=25000`; no `debtSettled`. |
| M7-10 | Segundo cobro de $25.000 | Segundo `paymentRegistered` con `remainingBalance=0` y **un** `debtSettled` con ambos balances `0`. |
| M7-11 | Reintento de publicación del mismo hecho | El mismo `eventId`; sin efectos duplicados. |
| M7-12 | Pago de M4 o municipal | Mismo nombre de evento, pero `originType` correspondiente; **sin** `infractionId` ficticio. |
| M7-13 | Un recibo distribuido en dos deudas | Un `paymentRegistered` por imputación, `paymentId` igual, `debtId` y `eventId` distintos. |
| M7-14 | Solicitud de reversión aún no ejecutada | Ningún `paymentReversed` publicado. |
| M7-15 | Revertir el segundo pago de $25.000 ya ejecutado | `paymentReversed` con `reversedAmount=25000` y saldo $25.000; deuda no cancelada. |
| M7-16 | Reversión de deuda antes cancelada | Reapertura contable visible sin inventar un evento nuevo; historial del pago intacto. |
| M7-17 | Una deuda `debtSettled` con varios módulos suscritos | Una **única** publicación, sin duplicación «por módulo». |
| M7-18 | `citizenId` ausente para obligación de una organización | No sustituirlo por DNI/CUIT; usar contrato opcional de M7 y preservar coherencia con otros suscriptores. |

### C. UI y permisos

| ID | Roles/pasos | Resultado esperado |
|---|---|---|
| M7-19 | M7 confirma multa → contribuyente ingresa portal M5 | Multa visible en la cuenta correcta con origen, patente, monto y saldo, **si** el titular está vinculado. |
| M7-20 | Cajero M5 registra pago parcial → contribuyente recarga | Saldo baja de $75.000 a $25.000; personal de Rentas y auditor ven el mismo movimiento. |
| M7-21 | Cajero termina cobro → contribuyente recarga | Saldo cero, estado cancelado, comprobantes e historial disponibles. |
| M7-22 | Cajero solicita reversión → Supervisor autoriza → se ejecuta | Antes de ejecutarla el saldo no cambia; luego saldo se restituye y `paymentReversed` se publica. |
| M7-23 | Ciudadano diferente manipula un ID/URL de deuda de tránsito | Backend impide ver o pagar obligaciones ajenas fuera de reglas de representación autorizada. |

## 8. Instrucción operativa para Codex — ejecutar sobre el repositorio

1. **Auditar primero** DTOs/envelopes, mappings `infractionConfirmed`, handler de consumo, entidades deuda/origen, registro de pagos/imputaciones, reversiones, capa de publicación, Core/RabbitMQ y permisos. Documentar diferencias por archivo.
2. **Modificar código M5 donde haga falta**; no crear una arquitectura paralela ni reemplazar funcionalidades que ya funcionan. Mantener convenciones del proyecto.
3. Implementar consumer `infractionConfirmed` conforme al JSON real M7, idempotencia por `eventId`/`infractionId`, matching DNI/CUIT y conciliación pendiente; `finalAmount` es el importe a cobrar, sin recalcular agravantes.
4. Adecuar el publisher económico **compartido**, sin clonar payloads para M7. Para `TRAFFIC_FINE` exigir `originId` e `infractionId` iguales. Respetar los campos exactos de `paymentRegistered`, `debtSettled` y `paymentReversed` de este documento.
5. Validar que cambiar pagos de tránsito **no rompa** eventos de M4 ni `debtSettled` de M8.
6. Preparar fixtures/contract tests con los JSON completos de este documento, pruebas de integración, pruebas de saldo, idempotencia, autorización y flujos UI si existen suites apropiadas.
7. Ejecutar build/lint/tests disponibles, resolver fallos introducidos y reportar aquellos preexistentes. Si no hay servicios externos levantados, usar tests locales/mocks y describir qué no se pudo verificar end-to-end.
8. Entregar resumen: **archivos modificados**, diferencias corregidas, tests ejecutados/resultados, rutas de UI afectadas, migraciones y **pendientes reales**. No atribuir cambios a M7: el contrato de M7 se respeta tal cual.

## 9. Criterios de aceptación de esta tarea

- [ ] M5 consume `infractionConfirmed` Core V4 y genera exactamente una deuda por `infractionId`.
- [ ] Se reconoce correctamente `DNI` y `CUIT`, sin mezclar identificadores externos e internos.
- [ ] `paymentRegistered` refleja el monto imputado y el saldo efectivo, también para pagos parciales.
- [ ] `debtSettled` solo se emite al cancelar, con `remainingBalance=outstandingBalance=0`.
- [ ] `paymentReversed` solo después de reversión ejecutada, con saldo correcto.
- [ ] Los tres publishers mantienen la estructura unificada compatible con M4/M7/M8.
- [ ] Envelope de Core exacto, con `sourceModule="rentas"` en todos los eventos M5.
- [ ] Duplicados, reordenamiento, reintentos y DLQ se manejan sin doble contabilización.
- [ ] Auditoría de cambios y visibilidad entre roles y portal ciudadano comprobadas.
- [ ] Build y tests ejecutados; cualquier limitación o problema se reporta de forma explícita.

---

**Restricciones finales:** No cambiar los eventos publicados por M7. No inventar `fineAmount`, `dueDate` o `citizenId` dentro de `infractionConfirmed`. No publicar un evento por cada módulo suscriptor. No dar por implementado nada sin inspeccionar el repositorio y probarlo.
