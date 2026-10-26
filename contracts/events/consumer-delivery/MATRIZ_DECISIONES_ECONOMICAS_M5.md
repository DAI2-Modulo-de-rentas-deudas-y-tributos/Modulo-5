# M5 — matriz de compatibilidad y decisiones externas pendientes

**Corte:** 26/10/2026. Es una matriz de coordinación derivada del handoff y propuestas locales. **No registra aceptación externa** ni modifica el contrato económico único M5. La especificación común permanece en [borrador UUID saliente](../M5_ECONOMIC_OUTBOUND_UUID_DRAFT.md), [propuesta de adaptación](../M5_ECONOMIC_ADAPTATION_M4_M7_M8_PROPOSAL.md) y [schema](../../schemas/m5-economic-outbound.proposed.schema.json). Fichas por destino: [M4](M5_A_M4_EVENTOS_ECONOMICOS.md), [M7](M5_A_M7_EVENTOS_ECONOMICOS.md), [M8](M5_A_M8_DEBT_SETTLED.md).

## Compatibilidad por consumidor

| Destino | Confirmado por las fuentes disponibles | Propuesta/cambio que debe revisar | Estado externo |
|---|---|---|---|
| **M4 Habilitaciones** | Fuente disponible: contrato Octubre 2026 marcado **versión de trabajo**. Los IDs de obligaciones M4 (`data.id`, por ejemplo `FEE-...`/`FINE-...`) son de M4 y M5 preserva exactamente `originId`. | `paymentId`/`debtId` UUID persistentes M5; perfil `PERMIT_FEE`/`COMMERCIAL_FINE`; `citizenId` opcional omitido o UUID canónico; `conceptCode`; correlación nula y orden/reversión. | **No ratificado**; aceptación del receptor y pruebas conjuntas pendientes. |
| **M7 Tránsito** | Referencia M7 Core V4 (19/10): `paymentId`/`debtId` son `string`; `infractionId` es identidad original de M7; M5 conserva `originId=infractionId`. | UUID M5 sigue siendo `string`, pero cambia semántica respecto de `payment-<Long>`/`debt-<Long>`; `conceptCode` exacto; presencia/omisión de `citizenId`; correlación, idempotencia y hechos fuera de orden. | **No ratificado**; compatibilidad semántica, parser e integración real no comprobados. |
| **M8 Desarrollo Social** | PDF disponible usa ejemplos prefijados (`debt-03200`, `c-00123`, `liq-01001`); M8 es consumidor previsto de `debtSettled`. | Para `MUNICIPAL_TAX`: UUID M5 de deuda/liquidación, UUID canónico M1 requerido y filtros por `originType`; política para deuda M4/M7 y organizaciones. | **Bloqueado**; `MUNICIPAL_TAX` sigue sin publicarse hasta acuerdo M8+M1+Core. |
| **Core/M9** | Handoff fija envelope de ocho campos: `eventId,eventType,eventVersion,occurredAt,sourceModule,correlationId,causationId,data`. Implementación local/outbox y ejemplos no demuestran entrega. | Estrategia de versión/transición, routing/filtros, ACK, idempotencia, correlation/causation, fuera de orden, retry/DLQ y aceptación de nulos. | **Pendiente**; sin transporte/ACK real confirmado. |

## Decisiones que deben resolverse antes de distribución/activación

| Decisión | Estado comprobable | Responsable que confirma |
|---|---|---|
| Un único evento y `data` compartido por consumidores; no eventos por receptor | Decisión de arquitectura documentada por M5; no es aceptación de cada consumidor. | M5 + Core + M4/M7/M8 |
| Envelope Core de ocho campos sin cambios | Decisión acordada en el handoff; no se propone agregar `producer`, `subject`, `payload` ni `targetModule`. | Core confirma recepción/transporte |
| UUID persistentes para entidades M5; PK Long internas | Propuesta/implementación local en M5; formato de receptor pendiente. | M4/M7/M8 según campos; Core para versionado |
| `originId` M4 original y `originId=infractionId` M7 | Correspondencia documentada en handoff; M5 debe preservar dato de origen. | M4/M7 prueban receptor y deduplicación |
| `citizenId` sólo UUID canónico M1; sin valor fabricado | Restricción M5; cobertura canónica M1 no confirmada para todos los casos. | M1 + cada consumidor |
| `eventVersion` | Ejemplos/código local muestran `1.0`; no equivale a número ratificado para UUID. No cambiarlo unilateralmente. | Core + todos los consumidores afectados |
| `MUNICIPAL_TAX` → M8 | Bloqueado en M5; el ejemplo JSON es sólo propuesta sintética. | M8 + M1 + Core |
| Envío efectivo | No se comprobó recepción/ACK, RabbitMQ/Core no activados. | Core + equipos consumidores |

## Diferencias y preguntas por equipo

### M4

- Confirmar UUID de `paymentId`/`debtId` y formato de transición de datos anteriores.
- Ratificar conservación literal del `originId` entrante M4, códigos de conceptos y filtro por `originType`.
- Decidir `citizenId` opcional/omitido y correlación nula en respuesta derivada.
- Acordar dedupe, reversal-before-payment, ACK/retry/DLQ y prueba conjunta.

### M7

- Confirmar UUID M5 textuales en los campos string de V4, sin alterar `infractionConfirmed`.
- Confirmar `originId=infractionId`, `conceptCode` correcto y omisión de `citizenId` cuando M1 no es verificable.
- Acordar correlación preservada, reversión/settlement fuera de orden, dedupe, ACK/retry/DLQ y prueba conjunta.

### M8 (y M1 para identidad)

- Ratificar o rechazar UUID de deuda/liquidación, `citizenId` canónico, semántica y catálogo municipal.
- Definir tratamiento de persona jurídica sin identidad canónica.
- Confirmar si Core debe entregar a M8 hechos M4/M7 y cómo ignorarlos/ACK sin aplicarlos al dominio M8.
- Acordar versión, orden, idempotencia y prueba conjunta; hasta entonces, conservar el bloqueo municipal.

### Core/M9

- Confirmar routing de los tres `eventType` y si filtra por `originType` o hace fan-out.
- Definir versión y transición compatibles; mantener el valor local sin cambios hasta esa decisión.
- Confirmar correlación/causación nullability, ACK de destino frente a escritura local, idempotencia, retry y DLQ.
- Proveer contrato/configuración de transporte y ventana de prueba antes de activar RabbitMQ.

## No inferir

- Fixtures, JSON Schema válido, outbox local `PUBLISHED` o suite verde **no** equivalen a aceptación M4/M7/M8/Core ni recepción externa.
- Los ejemplos de `MUNICIPAL_TAX` no autorizan publicación; el caso continúa bloqueado.
- No se cambia el envelope, los eventos entrantes, `eventVersion`, ni la semántica de los consumidores mediante esta matriz.
