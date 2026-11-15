# Backlog de continuación — M5 Rentas
> **DECISIÓN ACTUALIZADA:** consultar `06_UUID_SALIENTES_ACTUAL_VS_OBJETIVO.md` y `07_ENTRANTES_SALIENTES_CORE_Y_DISTRIBUCION.md`. **Preparar los cambios de IDs UUID en eventos SALIENTES M5 primero**; luego comunicar esos contratos a consumidores antes de activar Core. **Los contratos ENTRANTES de otros módulos no se modifican. Mantener SIEMPRE el envelope Core.**


**Corte:** 25/10/2026. No asumir que este plan implica autorización para migraciones/despliegues/conexiones externas. El responsable decide y aprueba cada hito.

## Reglas de ejecución para el nuevo Codex

- **Primero auditar el código CLONADO** y comparar con los reportes; no repetir refactorizaciones ya hechas ni confiar sólo en esta documentación.
- Una solicitud = diagnóstico → propuesta de cambio mínimo → código/pruebas locales (cuando autorizado) → reporte → revisión y commit/PR. Cambios destructivos a BD, actualizaciones de contratos comunes, conexión a terceros: permiso separado.
- Migraciones Flyway **sólo nuevas versiones** y probadas en réplica aislada y respaldada; no modificar V24/V25/V26 ya aplicadas, no ejecutar sobre `rentas` sin permiso explícito.
- Un cambio en **eventos salientes propios de M5** puede implementarse localmente tras aprobación del responsable, con matriz de impacto y regresión por consumidor; **después** se entregan los contratos actualizados a sus destinos y se coordina versión/adaptación. La aceptación externa es requisito para la **activación interoperable**, no para preparar el código. Eventos entrantes ajenos y envelope Core: no modificar sus contratos.
- Identidad de ciudadano propiedad de M1: sin match autoritativo, **DEFERRED**; nunca convertir `Long`/DNI/CUIT a UUID sintético.

## P0 — asegurar que el amigo recibió la versión correcta

**Objetivo:** repo íntegro, fuente confiable y reproducibilidad mínima.

**Acciones:**
1. Responsable verificar cambios no versionados y subir mediante commit; amigo clonar rama compartida; abrir PR desde branch propia.
2. Confirmar `git status --short`, `git log -1 --format=fuller`, última versión en `backend/src/main/resources/db/migration-postgresql`, `backend/INTEGRATION_CONTRACTS.md`, existencia de `DeferredEconomicReconciliation.java` y `DeferredExemptionReconciliation.java`.
3. Verificar JDK/Maven/Node requeridos desde `pom.xml`, `package.json` y configuración real; instalar dependencias sin incluir credenciales.
4. Ejecutar `cd backend && mvn test` y `cd frontend && npm test` como comandos base; si el proyecto necesita perfiles/flags, documentar comandos **reales** en el PR. No confundir 394/394 (ejecución antigua de quien entrega) con resultado del clon.
5. Preparar PostgreSQL propia/aislada, ejecutar migraciones desde cero y seed documentada; nunca copiar `.dump` con PII al repo.

**Criterio de aceptación:** clone compila, pruebas completas verdes y versión/archivos coinciden con entrega; diferencias registradas antes de desarrollar.

## P1 — M4 Habilitaciones (SIGUIENTE DESARROLLO)

**Fuentes:** M4 octubre en `contratos_originales/`, `02_CONTRATOS_DE_EVENTOS.md` y repositorio real.

**Tarea M4.1 — diagnóstico sin editar**
- Comparar `CoreM4Consumer`, payload inbound y construcción de deudas con `permitFeeGenerated` y `commercialFineGenerated`.
- Validar `data.id` estable, `permitApplicationId`, `establishmentId`, `actId`, `cuit`, importes, moneda, `reason`, `decidedAt`, `externalRef`, `sourceViolationId`/`sourceModule` condicionales.
- Distinguir `data.id` (origen estable) de `externalRef` (referencia documental); no sustituir los IDs M4 de texto con UUID propios.
- Probar dedup por `eventId` y por entidad fuente, eventos repetidos/fuera de orden, CUIT sin correspondencia y creación única de obligación.
- Comparar respuesta M5 `paymentRegistered`, `debtSettled`, `paymentReversed` para PERMIT_FEE/COMMERCIAL_FINE; pago parcial, total, cuotas, reversión.
- **Entregable:** matriz de diferencias, cambios mínimos, evidencias documentales, riesgos, posible migración y pruebas.

**Tarea M4.2 — implementar correcciones locales en consumidores M4 sin alterar contrato entrante; actualizar salidas de M5 conforme política UUID**
- Refactor sólo con autorización, reutilizando outbox/economic ledger de M7.
- Si falta `correlationId` oficial M4, conservar origen/estado sin fabricar causalidad; documentar dependencia y política cuando los equipos la acuerden.
- Agregar pruebas de regresión cruzadas con M7 y M8 y revisiones de seguridad/PII.

**Aceptación:** M4→M5 genera una sola obligación correcta; pagos/reversiones M5→M4 se serializan correctamente para sus dos `originType` y no se rompe ningún flujo M7/M8. Aceptación real por M4 sigue pendiente de prueba conjunta.

## P2 — contrato transversal único de eventos económicos (BLOQUEO ACTUAL)

**Consumos:** M4 y M7 de `paymentRegistered`/`paymentReversed`; M4/M7/M8 de `debtSettled`.

1. **Primero** diseñar e implementar en productores **M5** el contrato saliente objetivo de UUID persistentes para las entidades M5; medir impacto en M4/M7/M8, comparar tipos/semántica de los contratos disponibles y probar regresiones locales. M7 V4 acepta `string` y un UUID textual sigue siendo string, pero su consumidor puede depender de la representación.
2. Preparar UUID estable de `paymentId` si todavía no existe y utilizar UUID de `debtId` ya disponible V25; mantener PK `Long` y no inventar `citizenId` M1. Migraciones sobre `rentas` requieren autorización. **Después del cambio local**, emitir el contrato saliente final y entregarlo a todos los consumidores, con schema y `eventVersion` acordada antes de la activación real.
3. **NO tocar contratos ENTRANTES**. `originType=TRAFFIC_FINE`: `originId=infractionId` original M7; M4: `originId=data.id` de tasa/multa; `MUNICIPAL_TAX`: usar UUID propio de liquidación M5 si corresponde a su modelo, validar con pruebas y diferir hechos sin `citizenId` real.
4. Confirmar si eventos económicos de M4/M7 deben llegar a M8 cuando Core distribuye por `eventType`, y qué filtrado aplica cada receptor (`originType`). No crear `debtSettledForM8` ni otros eventos duplicados como parche.
5. Resolver `citizenId` opcional/nulo o su política para persona jurídica. No incluir DNI o ID M1 numérico en campo que deba ser UUID canónico.
6. **Implementar primero** un solo publisher M5 por evento compartido y actualizar fixtures JSON y pruebas por consumidor; **luego** entregar a M4/M7/M8 el esquema actualizado para adaptar receptores y coordinar versión/transición antes de Core. Mantener el `debtSettled` municipal aplazado si falta UUID canónico u otra información obligatoria; no activarlo prematuramente.

**Aceptación local:** contrato saliente M5 objetivo implementado, pruebas JSON/regresiones por origen y sin duplicación, envelope Core intacto; contrato remitido a consumidores con diferencias registradas. **Aceptación interoperable posterior:** consumidores adaptados, versión/transición confirmada, `debtSettled` municipal procesable sin impedir otros flujos.

## P3 — M1 Ciudadanos: correspondencia canónica y eventos propios

**Pendientes externos:**
- Identificador `citizenId` UUID canónico por cada ID numérico M1 previo; método/evento/API de equivalencia **autoritativa** y tratamiento de inexistentes/duplicados.
- UUID de organizaciones/representaciones; qué representa `organizationId`, `cuit`, `taxId`, `personId`; empleados externos y origen de `updatedBy.id`.
- Envelope M1 actualizado, sourceModule, eventId y versión; compatibilidad de JWT de usuario HS256 con JWT máquina RS256 de Core (usos distintos).
- Confirmación de los cuatro contratos de recepción **desde M5**: `exemptionRequested`, `updateExemptionStatus`, `paymentPlanRequested`, `updatePaymentPlanStatus`; tipos, estados y nulos.
- Confirmación con M8 de `benefitId`/`applicationId`/`programId` UUID canónicos y rechazo de exención.

**Tareas M5 después del acuerdo:**
1. Proyección verificable M1 ID legado → UUID canónico sin cambiar PK `Long`; migración de correspondencias en base aislada, respaldo, ejecución autorizada.
2. Reconciliar beneficios sociales/tickets/exenciones/overdueDebt diferidos usando **mismo ciudadano real**; registrar auditoría y no generar eventos repetidos.
3. Revisar recepción `citizenUpdated`, `organizationRegistered`, `representationGranted`, `representationExpired`, actualizaciones de estado, bloqueos, CUIT; pruebas de acceso y asociación.
4. Revisar eventos de planes M5→M1. V25 creó UUID persistente de deuda/liquidación/solicitud/exención; confirmar que `planId` estable exista antes de emitir la propuesta de `updatePaymentPlanStatus`.

**Aceptación:** el mismo ciudadano se identifica sin ambigüedad en M1/M2/M5/M8; eventos de M5 no contienen UUID inventados; solicitudes de planes/exenciones alcanzan los consumidores con versión aceptada.

## P4 — controles locales restantes de M7 y M8

- **M7:** smoke test de **reinicio físico real** en entorno aislado con evento económico DEFERRED persistido: parar backend, iniciar otro proceso, conciliar con evidencia original, verificar no duplicación/immutabilidad del movimiento. Tests actuales probaron transacciones posteriores, no reinicio de proceso.
- **M7:** inventariar históricos sin snapshots. Imputación histórica N.º 3 tiene cuota CANCELLED e imputación ACTIVE; bloqueo de reversión ya implementado y datos sin tocar. Otros casos sólo mediante conciliación manual con respaldo documental; sin prorrateo estimado.
- **M7:** confirmar catálogo de `infractionType`, semántica `conceptCode=TRAFFIC_INFRACTION` vs ejemplo `TRAFFIC_FINE` y formato de `citizenId` para deuda de infracción.
- **M8:** `DeferredExemptionReconciliation` por Supervisor: probar con UUID M1 real cuando exista, no sólo fixture; confirmar `REJECTED` y vigencias/nulos con M8. Conciliación por lote era **opcional**, no blocker funcional.
- **M2:** mantener v1.78 local; validación real de transiciones y mensajes se hace cuando M2 esté conectado.

## P5 — CORE/RabbitMQ (sólo después de contratos alineados)

**No comenzar este bloque hasta autorización del responsable.** Necesitamos el documento/guía final Core real, no inventar credenciales/URLs.

1. Confirmar exchange `muni.inbox`, topología real por evento y colas `q.rentas`, permisos, bindings, usuario de servicio, TLS/red y vhost, fuente de verdad de las suscripciones. M2 v1.78 describe fanout durable/colas individuales como patrón de referencia; validar con equipo Core real.
2. Token de **módulo** JWT RS256 de 15 minutos, rotación/renovación, token distinto al de usuario M1 HS256; origen `sourceModule="rentas"`.
3. Reemplazar `LocalLogEventPublisher` por transport real con outbox, confirmaciones de broker/aceptación Core y errores/retry. NO marcar `PUBLISHED` por escribir log; distinguir `OUTBOX_PREPARED`, entrega broker y recepción de destino.
4. Consumidor de `q.rentas` por `eventType`, idempotencia por `eventId`, ack de evento válido irrelevante, retry de fallas transitorias, DLQ para problemas definitivos, manejo de schemas/versiones incompatibles, no depender de orden.
5. Correlación/causalidad en todos los eventos derivados, incluyendo tickets/exenciones/pagos y eventual conciliación.
6. Pruebas contra Core real/sandbox con por lo menos una acción por módulo y evidencia trazable en productores, Core, consumidores y portales.

## P6 — E2E multisistema y verificación visual de portales

Usar `04_PLAN_DE_PRUEBAS_E2E.md`. Pruebas de workflow real M1↔M2↔M5, M4↔M5, M7↔M5, M8↔M5, incluyendo cuotas, reversiones y actualización UI tras refresco de datos. **No declarar integrado** hasta observar cambio real en base destino/portal correspondiente. Registrar defectos y repetir regresiones.

## P7 — cierre y documentación de entrega

- Completitud de contratos y aceptación escrita de cada equipo, pruebas automáticas/integ/E2E, README reproducible de arranque, evidencia de migraciones y rollback/restore seguro.
- PR revisados, commits legibles, ramas limpias; nada de `.env`, JWT, claves, PII, dumps o logs sensibles.
- Matriz final por evento: `contrato acordado`, `publicado`, `aceptado por Core`, `consumido`, `reflejado en portal`; no equiparar estas cinco cosas.

## Prompt corto reutilizable para Codex en el clon

> Leé `docs/handoff/00_LEER_PRIMERO.md`, `01_ESTADO_Y_ARQUITECTURA.md`, `02_CONTRATOS_DE_EVENTOS.md`, `03_BACKLOG_PRIORIZADO.md`, `04_PLAN_DE_PRUEBAS_E2E.md`, `06_UUID_SALIENTES_ACTUAL_VS_OBJETIVO.md`, `07_ENTRANTES_SALIENTES_CORE_Y_DISTRIBUCION.md` y el AGENTS.md de raíz. Primero auditá esta rama real y los tests sin modificar archivos; contrastá el código con los contratos originales, separá acuerdos ratificados de propuestas y reportá diferencias. Luego proponé la tarea siguiente más acotada del backlog, con criterios de aceptación y pruebas. No ejecutes migraciones sobre `rentas`, no hagas commit/push ni conectes Core sin autorización. Podés proponer cambios a eventos SALIENTES M5 y, al ser autorizados, implementarlos **primero localmente**, pasar luego los contratos a todos los consumidores y validar adopción antes de Core. **Nunca cambies contratos de eventos ENTRANTES ajenos ni inventes UUID de otros módulos. Envelope Core siempre intacto.**
