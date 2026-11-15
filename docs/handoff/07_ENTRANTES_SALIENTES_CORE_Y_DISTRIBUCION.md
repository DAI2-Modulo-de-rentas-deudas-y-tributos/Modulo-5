# Manual operativo: qué cambia M5, qué NO cambia y cómo entregar contratos a consumidores

**Decisión de arquitectura de M5 (25/10/2026).** Leer junto con `06_UUID_SALIENTES_ACTUAL_VS_OBJETIVO.md` y `02_CONTRATOS_DE_EVENTOS.md`.

## Propiedad y capacidad de cambio

| Propietario del evento | Dirección respecto de M5 | Qué puede hacer Codex en el repo M5 | Qué NO debe hacer |
|---|---|---|---|
| M1 | Entrante | Ajustar mapper, validación, proyección y compatibilidad interna con `citizenUpdated`, `organizationRegistered`, `representationGranted`, `representationExpired` | Editar su contrato de eventos; inventar UUID/IDs de M1; exigirle que cambie formato sólo para funcionar localmente |
| M2 | Entrante | Consumir `ticketUpdated` exactamente como figura en M2 v1.78, filtrar, proyectar y probar | Renombrar campos, generar ticketId diferente, cambiar la semántica de ROUTED |
| M4 | Entrante | Aceptar `permitFeeGenerated` y `commercialFineGenerated` con su `data.id` original, adaptar nuestra base | Cambiar `FEE-...` / `FINE-...` por UUID propio; reemplazar origen por `externalRef` |
| M7 | Entrante | Aceptar `infractionConfirmed`, conservar `infractionId` y `correlationId` original, crear deuda sin duplicar | Cambiar campos, UUID o DNI/CUIT que publica M7 |
| M8 | Entrante | Aceptar `socialBenefitUpdated`, guardar sus referencias y aplicar reglas autorizadas localmente | Generar UUID sustitutos para `benefitId`, `applicationId`, `programId`, `citizenId` |
| **M5** | **Saliente** | **Actualizar DTOs/serialización y datos técnicos a UUID estables M5, probarlos localmente, exportar el contrato y comunicarlo a receptores** | Cambiar envelope Core, inventar referencias ajenas, publicar salidas incorrectas/activarlas antes de integrar receptores |

## Proceso de cambio de los eventos salientes (paso a paso)

**Paso A — implementar primero en M5.** Auditar DTO/publisher/tabla, reutilizar UUID ya persistido y proponer UUID estable de nuevas entidades que carezcan de él. Mantener PK `Long`, idempotencia, monto/saldo, trazabilidad, `originId` real del módulo propietario. Cambios de BD: scripts Flyway nuevos, prueba aislada y autorización expresa antes de ejecutarlos en `rentas`.

**Paso B — pruebas locales.** Fixtures JSON del envelope Core y `data` esperado, regresión por `originType` y consumidor. No confundir pruebas verdes con recepción real.

**Paso C — emitir el contrato desde M5.** Para cada evento entregar un archivo de contrato con: `eventType`, `eventVersion`, envelope intacto, definición de `data`, tipos, required/nullable, ejemplos completos, enumeraciones, identidad UUID propiedad M5 vs ID de origen externo, reglas de negocio, causalidad, `originType`, idempotencia y datos de prueba. Llevar control de fecha y versión.

**Paso D — pasarlo a todos los módulos consumidores.** M4 y M7: `paymentRegistered`, `paymentReversed`; M4/M7/M8: `debtSettled`; M1/M8: exenciones; M1: planes; M8: `overdueDebt`; M2: `updateTicketStatus`. Confirmar quién recibió/adecuó cada contrato. Si existe desacuerdo, ajustar coordinación o versionado **sin retroceder silenciosamente los cambios locales ni activar flujos incompatibles**.

**Paso E — integración y portales, último hito.** Sólo cuando los destinos estén listos y exista la guía actual de Core: conectar RabbitMQ, demostrar el transporte real, y ejecutar E2E para comprobar cambios visibles en cada portal.

## Envelope Core que jamás se reemplaza

```json
{
  "eventId": "c4e8fabe-fc39-4870-a2e8-1b981bc7c531",
  "eventType": "paymentRegistered",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-25T18:30:00-03:00",
  "sourceModule": "rentas",
  "correlationId": null,
  "causationId": null,
  "data": {}
}
```

Este JSON muestra **sólo el envelope** (el contenido `data:{}` no es un pago válido). Es obligatorio mantener ocho nombres y niveles de campos; no poner `producer`, `subject`, `payload` o `targetModule` al mismo nivel. `eventVersion` es un campo del envelope, no permiso para inventar otro; su **valor** se coordina con Core cuando una revisión de `data` exija nueva versión.

## Registro por contrato compartido

| Evento propio M5 | Consumidores a notificar después del cambio local |
|---|---|
| `paymentRegistered` | M4, M7 |
| `debtSettled` | M4, M7, M8 |
| `paymentReversed` | M4, M7 |
| `exemptionRequested` | M1, M8 |
| `updateExemptionStatus` | M1, M8 |
| `paymentPlanRequested`, `updatePaymentPlanStatus` | M1 |
| `overdueDebt` | M8 |
| `updateTicketStatus` | M2 |

### Criterios de aceptación (antes de activar Core)

- Los eventos entrantes reconocidos conservan el contenido y tipo publicados por sus productores; los adapters internos admiten esa interfaz.
- Todas las salidas M5 relevantes usan UUID **de la entidad M5 correspondiente**, sin re-generación por evento o reintento, y sin cambiar PK `Long`.
- `originId`, `infractionId`, `ticketId`, `citizenId`, `benefitId` y otros identificadores **ajenos** se conservan tal como el módulo propietario los asignó; las relaciones no verificadas producen `DEFERRED`, no IDs ficticios.
- Envelope Core exacto (ocho campos) en todas las salidas M5; correlación auténtica.
- Pruebas JSON por `eventType`, `originType`, estado y consumidor; sin duplicación de movimientos económicos.
- Contratos salientes distribuidos, diferencias/versiones registradas y receptores actualizados o transición coordinada.
- Solo entonces habilitar envío real vía Core y comprobar actualización visible entre portales.
