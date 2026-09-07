# Auditoría del contrato preliminar M4 → M5

Fecha: 2026-08-31
Estado: contrato preliminar incorporado en el adaptador; efecto económico bloqueado por falta de identificación del contribuyente.

## Alcance y decisiones

- Core es el hub. M5 no incorpora URL, token, topic, queue ni endpoint REST directo para M4.
- El envelope `{module,event,data}` es de transporte y se normaliza sin modificar el contrato M7.
- `PENDING_CORE_ENVELOPE_ALIGNMENT` / `CORE_ENVELOPE_GAP`: Core debe confirmar el envelope final, incluido transport event id y timestamp.
- La clave idempotente preliminar del inbox es `M4:<event>:<data.id>`. El UUID persistido es sólo una clave técnica determinística, no un event ID emitido por M4/Core.
- `BLOCKED_M4_TAXPAYER_RESOLUTION` / `M4_TAXPAYER_RESOLUTION_GAP`: `establishmentId` no permite resolver hoy un `TaxpayerReference` con evidencia contractual.
- Mientras exista el gap, M5 conserva el payload en `IntegrationEventLog` con estado `FAILED` y no crea `ExternalObligation`, `Debt` ni un contribuyente ficticio.

## Inbound: permitFeeGenerated

| Campo M4 | Tipo | Implementación M5 | Estado | Acción |
|---|---|---|---|---|
| `module` | string | `Envelope.module`; exige `M4` | MATCH | Confirmar envelope final con Core |
| `event` | string | `Envelope.event`; exige `permitFeeGenerated` | MATCH | Confirmar envelope final con Core |
| `data.id` | string obligatorio | `PermitFeeGeneratedData.id`; clave de negocio | MATCH | Mantener como referencia M4 |
| `permitApplicationId` | string obligatorio | Campo contractual y payload trazable | MATCH | Ninguna |
| `establishmentId` | string obligatorio | Campo contractual y payload trazable | MATCH / BLOCKED | Acordar resolución hacia contribuyente |
| `amount` | BigDecimal > 0 | `@Positive BigDecimal`; no se recalcula | MATCH | Ninguna |
| transport event id | no informado | No se inventa; se usa clave técnica interna | PENDING_CONTRACT | Core debe definirlo |
| occurred at | no informado | `receivedAt` técnico para el log | PENDING_CONTRACT | Core debe definirlo |
| taxpayer identity | no informado | No existe proyección contractual por establecimiento | MISSING | Resolver con M4/Core/PO |
| due date | no informado | No se inventa | MISSING | Confirmar si la obligación final la requiere |

Implementación previa auditada: `MISMATCH`. Exigía `taxpayerType`, `taxpayerExternalId` y `dueDate`, campos ausentes en M4, y podía crear efecto económico. Ese consumidor M4 genérico fue retirado.

## Inbound: commercialFineGenerated

| Campo M4 | Tipo | Implementación M5 | Estado | Acción |
|---|---|---|---|---|
| `module` | string | `Envelope.module`; exige `M4` | MATCH | Confirmar envelope final con Core |
| `event` | string | `Envelope.event`; exige `commercialFineGenerated` | MATCH | Confirmar envelope final con Core |
| `data.id` | string obligatorio | `CommercialFineGeneratedData.id`; clave de negocio | MATCH | Mantener como referencia M4 |
| `sourceViolationId` | string condicional | Opcional; requiere `sourceModule` si aparece | MATCH | Confirmar regla final de coherencia |
| `sourceModule` | string condicional | Opcional; requiere `sourceViolationId` si aparece; no fuerza M6 | MATCH | Ninguna |
| `establishmentId` | string obligatorio | Campo contractual y payload trazable | MATCH / BLOCKED | Acordar resolución hacia contribuyente |
| `actId` | string obligatorio | Campo contractual y payload trazable | MATCH | Ninguna |
| `amount` | BigDecimal > 0 | `@Positive BigDecimal`; no se recalcula | MATCH | Ninguna |
| `reason` | string obligatorio | Campo contractual y payload trazable | MATCH | Ninguna |
| `decidedAt` | ISO 8601 con offset | `OffsetDateTime`; conserva el instante | MATCH | Ninguna |
| `externalRef` | string obligatorio | Campo contractual y payload trazable | MATCH | Ninguna |
| transport event id / occurred at | no informados | No se inventan como datos contractuales | PENDING_CONTRACT | Core debe definirlos |
| taxpayer identity | no informado | No existe proyección contractual por establecimiento | MISSING | Resolver con M4/Core/PO |
| due date | no informado | No se inventa | MISSING | Confirmar si la obligación final la requiere |

Implementación previa auditada: `MISMATCH`, por el mismo DTO genérico incompatible con M4.

## Idempotencia y persistencia

| Elemento | Resultado |
|---|---|
| Business key permit fee | `M4:permitFeeGenerated:<id>` |
| Business key commercial fine | `M4:commercialFineGenerated:<id>` |
| Duplicado mientras está bloqueado | Devuelve el mismo `IntegrationEventLog`; no duplica logs ni efectos |
| Constraint de obligación final | Ya existe unique (`source_module`,`external_type`,`external_reference_id`) |
| Migración nueva | No requerida; V1–V12 permanecen intactas |
| Datos M4 propios | No se crean tablas Permit/Establishment/Inspection/Fine/Act |

Cuando exista resolución válida del contribuyente, una obligación final deberá usar conceptualmente `M4 + PERMIT_FEE/COMMERCIAL_FINE + data.id`. Esta auditoría no implementa esa resolución ni una fecha de vencimiento inexistente.

## Eventos M4 expresamente no consumidos

`permitUpdate` —incluido `APPROVED`— no genera deuda. Tampoco se agregan consumidores para `permitApplicationStarted`, `permitDocumentationReviewed`, `inspectionUpdate`, `closureUpdate` ni `updateTicketStatus` por esta tarea.

## Outbound esperado M5 → M4

| Evento | Implementación actual | Estado contractual | Gap / acción |
|---|---|---|---|
| `paymentRegistered` | Existe evento genérico `PLATFORM` con paymentId, taxpayerId y amount | PARTIAL + PENDING_PAYLOAD_CONTRACT + PENDING_CORE_ROUTING | No identifica `FEE-001`/`FINE-001`; acordar correlación, payload y routing Core |
| `debtSettled` | Existe contrato dirigido a M8, no a M4 | PARTIAL + PENDING_PAYLOAD_CONTRACT + PENDING_CORE_ROUTING | No incluye la referencia de obligación M4; no reutilizar arbitrariamente el contrato M8 |
| `paymentReversed` | El dominio revierte pagos, pero no existe evento outbound M4 confirmado | PENDING_M4_FINAL_CONFIRMATION + PENDING_PAYLOAD_CONTRACT + PENDING_CORE_ROUTING | Acordar existencia, correlación y payload antes de implementarlo |

No se modificaron payloads ni rutas outbound basándose en inferencias.

## API y seguridad impactadas

- No existe `POST /api/v1/events*` ni endpoint REST M4 → M5.
- No existe POST público para crear `ExternalObligation`; sólo consultas y retry autorizado de obligaciones ya persistidas.
- Los eventos bloqueados se consultan por IntegrationEventLog (`sourceModule=M4`, `eventType` y estado) y su reproceso técnico permanece bloqueado hasta resolver el contrato.
- External obligations finales conservan filtros de origen/tipo/referencia; esta fase no expone una obligación M4 inexistente.
- Debt y Audit no recibieron cambios.
- El payload de integración continúa restringido a roles técnicos/supervisión/auditoría conforme al API existente.

## Gaps contractuales

### M4_TAXPAYER_RESOLUTION_GAP

`permitFeeGenerated` y `commercialFineGenerated` identifican el establecimiento mediante `establishmentId`, pero no incluyen identificador directo de la persona u organización responsable. Para crear `Debt`, M5 necesita que el contrato defina una de estas opciones, sin elegirla localmente:

1. `organizationId` / `citizenId`;
2. DNI / CUIT;
3. una proyección contractual previa `establishmentId → taxpayer`.

### CORE_ENVELOPE_GAP

M4 documenta preliminarmente `module`, `event`, `data`; M7 posee otro envelope. Core debe confirmar el envelope final común, IDs, timestamps, correlación y routing. El adapter actual mantiene separado el transporte del evento normalizado.

## Variables de entorno y secretos

Sin cambios en variables de entorno ni secretos. No se agregaron `M4_URL`, `M4_TOKEN`, `M4_SECRET` ni configuración de broker.

## Validación local

- `mvnw clean verify`: `BUILD SUCCESS`.
- Tests detectados: 97; aprobados: 97; failures: 0; errors: 0; skipped: 0.
- PostgreSQL 17.11 real fue validado con `postgres:17-alpine`. La suite Testcontainers ejecutó 11/11 escenarios, incluido el contrato preliminar M4 sin efectos económicos.
- Cobertura de líneas: 814/922 (88,29%); gate JaCoCo >=85% cumplido.
- Cobertura de instrucciones: 17.637/20.298 (86,89%).
- OpenAPI: 122 paths / 136 operaciones, sin endpoints públicos `/events/*`.
- Flyway: V1–V12 validadas; no se creó V13.

## Pregunta pendiente

### PREGUNTA M4-01

> En permitFeeGenerated y commercialFineGenerated recibimos establishmentId,
> pero M5 necesita identificar al contribuyente responsable de la obligación.
> ¿Cómo debemos resolver establishmentId -> contribuyente?
>
> ¿M4 enviará organizationId/citizenId o DNI/CUIT en el evento,
> o existe otro contrato/evento mediante el cual M5 debe mantener previamente
> la relación establecimiento -> titular?
