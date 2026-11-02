# Adaptación receptora de eventos económicos M5 — propuesta, no distribuida

**Corte:** 26/10/2026. El objetivo saliente M5 está aprobado internamente; este documento **no acredita aceptación de M4, M7, M8 ni Core**. Fuentes contrastadas: contrato económico único de M5, resúmenes de M4 octubre/M7 Core V4/M8 PDF en el handoff y las propuestas M5→M8 anteriores (`docs/handoff/propuestas_no_ratificadas/M5_M8_UUID_PROPUESTA.md` y `M5_M8_EXENCIONES_DISENO_Codex.md`). La comparación no sustituye confirmación con los equipos propietarios. No modifica contratos entrantes de esos módulos ni el envelope de Core.

## Un único mensaje por hecho, distribuido por Core

| Evento M5 | Receptores a coordinar | Identidad económica | Filtro mínimo |
|---|---|---|---|
| `paymentRegistered` | M4 y M7 | `paymentId` UUID M5, `debtId` UUID M5, imputación individual | M4: `PERMIT_FEE`/`COMMERCIAL_FINE`; M7: `TRAFFIC_FINE`. |
| `debtSettled` | M4, M7 y M8 | `debtId` UUID M5, saldo 0 | Cada receptor filtra `originType`; `MUNICIPAL_TAX` para M8 sigue **bloqueado**. |
| `paymentReversed` | M4 y M7 | Mismos IDs del pago original, porción revertida | M4/M7 filtran como arriba. |

M5 no enviará copias diferentes por receptor ni creará `debtSettledM8`. Si Core distribuye por `eventType`, un receptor puede recibir un `originType` ajeno: debe reconocerlo y hacer ACK/ignorar sin aplicarlo a otra obligación. La topología real y esa política de ACK requieren acuerdo con Core.

## Diferencias frente a contratos y ejemplos anteriores

| Aspecto | Referencia anterior | Propuesta única M5 | Consecuencia / aceptación necesaria |
|---|---|---|---|
| `paymentId`, `debtId` | Ejemplos anteriores `payment-<Long>`/`debt-<Long>`; M7 V4 tipa ambos como `string`; PDF M8 usa `debt-03200`. | UUID textual **persistente de la entidad M5**; PK `Long` queda interna. | Cambio semántico aunque siga siendo JSON `string`. M4/M7/M8 deben revisar parseo, índices, persistencia y deduplicación. |
| `originId` M4 | `FEE-001`/`FINE-001`, distinto de `externalRef`. | **Sin cambio**: `data.id` original M4. | M4 no debe buscar por `debtId` ni por `externalRef` para localizar la obligación. |
| `originId` M7 y `infractionId` | UUID de `infractionConfirmed`. | Ambos iguales al UUID original de M7. | M7 valida igualdad; no reemplazar por UUID de deuda M5. |
| `originId` municipal | PDF M8 ilustraba `liq-01001`; propuesta M5→M8 posterior planteó UUID de liquidación. | `liquidation.integration_id` UUID textual **persistente M5**. | M8 debe aceptar el nuevo formato y confirmar si usa esa identidad para conciliar. |
| `citizenId` externo | M4 documento de trabajo lo omite; M7 V4 lo describe opcional `string|null`; borrador M5 anterior mostraba `null`. | **Omitido** si no hay UUID M1 verificado; UUID válido si lo hay; nunca `null` bajo la propuesta actual. | M4 confirma campo adicional si aparece; M7 confirma omisión; M8 no debe inferir identidad desde una ausencia. |
| `citizenId` municipal | PDF M8 ejemplifica `c-00123`; propuesta UUID M5→M8 permitía `null` para organización. | UUID M1 canónico **requerido para publicar `MUNICIPAL_TAX`**; caso sin él permanece diferido. | M8/M1 deben confirmar asociación y política de persona jurídica. No usar `null` u omisión como atajo. |
| `eventVersion` | Ejemplos y código local muestran `1.0`. | **Sin número nuevo unilateral.** El schema propuesto no fija el valor. | Si anterior versión no se activó, Core puede ratificar `1.0`; si existe un receptor activo, coordinar versión/corte/compatibilidad antes del envío. |
| `correlationId` M4 | Envelope permite UUID o `null`; política de respuesta M4 no cerrada. | Preservar UUID de origen si existe; **no fabricar** si fue `null`. | M5 hoy difiere el evento económico cuando no puede recuperar UUID. Core/M4 deben decidir aceptación de respuesta con `null` o aportar correlación autoritativa. |

La propuesta M5→M8 sobre `overdueDebt` conserva la regla de `debtId` UUID estable: si luego se publicara `debtSettled` de esa misma deuda, debe ser **el mismo** `debtId`. Los contratos de exenciones M5→M8 no se alteran aquí. El PDF M8 ejemplifica `debtSettled` municipal, pero no prueba aceptación de M4/M7 ni define nulos para personas jurídicas; no se le atribuye ese acuerdo.

La propuesta previa `M5_UUID_SALIENTES_OBJETIVO.md` mostraba un `originId` UUID para M4 **sólo si M4 lo emitía y reconocía**. Esa condición no está cumplida por el ejemplo `FEE-001`/`FINE-001`; por tanto, la versión única de este paquete conserva el string M4 original y no promete un UUID ajeno. También sustituye los ejemplos anteriores de `citizenId:null` por omisión para externos sin vínculo M1 y retención completa del caso municipal sin UUID M1. Ninguna de estas decisiones cambia el evento entrante de M4 o el PDF de M8.

## Matriz `citizenId` de `debtSettled`

| Receptor / origen | Omitido | `null` explícito | UUID M1 canónico |
|---|---|---|---|
| M4 / `PERMIT_FEE`, `COMMERCIAL_FINE` | Forma propuesta sin vínculo M1; coincide con omisión del documento M4. | Campo extra no acordado; evitar. | Extra no documentado; M4 debe decidir si lo ignora o aprovecha. |
| M7 / `TRAFFIC_FINE` | Sintácticamente compatible con opcional V4, sujeto a prueba receptora. | V4 parece admitirlo, pero M5 **no** lo emitirá sin revisión. | JSON `string` válido; verificar vínculo M1 y semántica del receptor. |
| M8 / M4/M7 recibidos por suscripción | No se presume procesable; filtrar/ignorar o retener según acuerdo. | No se presume procesable. | M8 debe definir si estos orígenes son de su dominio; no tratar todo `debtSettled` como tributo municipal. |
| M8 / `MUNICIPAL_TAX` | **Bloqueado**, no publicar. | **Bloqueado**, no publicar. | Variante propuesta, aún bloqueada por aceptación e identidad verificable. |

La forma actual de M5 es omisión por `@JsonInclude(NON_NULL)`. El schema de propuesta rechaza `null` y, para `MUNICIPAL_TAX`, exige UUID presente. Se requiere confirmación bilateral para cualquier política diferente.

## Adaptación solicitada a cada receptor

### M4 Habilitaciones

- Aceptar `paymentId`/`debtId` como UUID textuales M5 sin convertir `originId` a UUID; usar `originType + originId` para encontrar la tasa/multa propia. `externalRef` no sustituye `originId`.
- Procesar `amountApplied`/`reversedAmount` por imputación y `remainingBalance` por deuda; un mismo pago puede producir más de un mensaje. `debtSettled` sólo describe saldo cero de **esa** deuda.
- Confirmar si acepta `citizenId` omitido o UUID opcional, y respuesta con `correlationId:null` cuando el evento M4 de origen lo trajo nulo. M5 no inventará correlación para desbloquear movimientos.
- Confirmar los `conceptCode` de tasa y multa usados al cancelar; los nombres de los ejemplos de este paquete no fijan ese catálogo.
- Deduplicar por `eventId`; retener temporalmente reversión/cancelación anterior al pago y conciliar después. No aplicar una reversión dos veces.

### M7 Tránsito

- Aunque V4 declare IDs económicos como `string`, revisar cualquier validación que espere `payment-`/`debt-`; nuevos valores son UUID textuales estables. Mantener `originId=infractionId` original M7.
- Aceptar `citizenId` omitido si no hay M1 verificado, o UUID M1 si existe; no confundirlo con `debtorId` DNI/CUIT. Confirmar `conceptCode` (`TRAFFIC_INFRACTION` local frente a `TRAFFIC_FINE` del ejemplo V4).
- Conservar correlación original de `infractionConfirmed`. El flujo M7 sin evidencia verificable permanece diferido en M5. Deduplicar y tolerar orden variable, en particular reversión posterior a cancelación.

### M8 Desarrollo Social

- Adaptar `debtSettled` al UUID persistente M5 y filtrar explícitamente `originType`. No crear una deuda municipal a partir de un evento `TRAFFIC_FINE`/M4 sólo porque comparte `eventType`.
- Para `MUNICIPAL_TAX`, aceptar `debtId` UUID M5 (igual al de `overdueDebt`), `originId` UUID de liquidación M5 y `citizenId` UUID **canónico M1**; `infractionId` ausente. Confirmar tratamiento de personas jurídicas y política para mensajes de otros orígenes. M5 no publica esta variante hasta el acuerdo.
- El PDF M8 antiguo usa identificadores de ejemplo `debt-03200`, `c-00123`, `liq-01001`; la propuesta UUID anterior los reemplazaba, pero admitía `citizenId:null` para organizaciones. Esta versión única **retiene** esos casos sin UUID verificable en vez de enviarlos con `null`. M8 debe ratificar esa diferencia.
- Tolerar `debtSettled` antes de otros hechos relacionados, idempotencia por `eventId` y consistencia eventual; no interpretar orden de llegada como orden económico.

## Orden, dinero y control de duplicados

El contrato no promete orden global ni exactamente una entrega. Cada receptor debe registrar `eventId` procesado con restricción única, hacer ACK del duplicado y aplicar cambios idempotentes. Si un hecho depende de otro aún no visto, conservarlo como pendiente con timeout/revisión y procesarlo al llegar el antecedente, sin descartar ni modificar cifras. La clave de negocio complementaria es `paymentId + debtId + eventType` y, si un mismo pago tiene imputaciones posteriores a la misma deuda, también la identidad del evento/imputación; **no** deduplicar sólo por `paymentId`. Los deltas monetarios se aplican una vez; `remainingBalance` es evidencia del momento del hecho, **no** el saldo actual para sobrescribir según el orden de llegada. `occurredAt` tampoco sustituye una secuencia monotónica de deuda, hoy ausente; si el receptor no puede reconstruir desde su ledger, deberá acordar conciliación autoritativa con M5.

Montos JSON `number` corresponden a `BigDecimal` de dos decimales. No usar aritmética binaria de `float` para dinero. Cada `paymentRegistered` informa la porción imputada y saldo posterior de una deuda; una transferencia repartida entre M4 y M7 produce dos hechos con el mismo `paymentId`, distintos `debtId`, `eventId`, importes y saldos. `paymentReversed` revierte la porción original de cada deuda cuando la reversión fue ejecutada; puede llegar después de `debtSettled` y volver a dejar saldo positivo. Un `debtSettled` ya observado queda en el historial, no se borra ni se reinterpreta como saldo actual.

## Aceptaciones que faltan antes de distribuir/activar

| Responsable | Decisión requerida |
|---|---|
| Core + M4/M7/M8 | `eventVersion` y transición de IDs; formato/consumo de ocho campos sin modificar envelope ni nombre de eventos. |
| Core + M4 | Respuesta económica con `correlationId:null` si el origen M4 fue nulo; causalidad/retención de snapshots. |
| M4 | UUID económicos, campo `citizenId` opcional, reversión y hechos fuera de orden. |
| M7 | UUID económicos dentro de `string`, `citizenId` opcional, catálogo `conceptCode`, reversión y orden. |
| M8 + M1 | UUID canónico, variante `MUNICIPAL_TAX`, personas jurídicas y filtrado de `originType`. |
| M5 | Revisar históricos con IDs prefijados y snapshots diferidos; nunca re-publicarlos silenciosamente. |

Ninguna fila está marcada como aceptada por un receptor. No se ha activado RabbitMQ ni publicado un evento real.
