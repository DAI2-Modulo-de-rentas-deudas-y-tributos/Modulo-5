# AGENTS.md — Instrucciones para Codex: Proyecto M5 Rentas

> **Destino:** raíz del repositorio después de revisión manual. Si ya existe `AGENTS.md`, fusionar cuidadosamente, no sobrescribir reglas del proyecto.

## Contexto a leer ANTES de implementar

Consultar `docs/handoff/00_LEER_PRIMERO.md`, `01_ESTADO_Y_ARQUITECTURA.md`, `02_CONTRATOS_DE_EVENTOS.md`, `03_BACKLOG_PRIORIZADO.md`, `04_PLAN_DE_PRUEBAS_E2E.md` y los contratos originales indicados ahí. Si cambian las rutas, resolverlas en el repo. **Comparar siempre esos reportes históricos con el código de la rama actual** y mencionar discrepancias.

## Reglas NO negociables

1. **Primero diagnosticar; después cambiar** sólo el alcance autorizado. En nuevos módulos, comenzar por revisión de DTOs, consumidores, publishers, servicios, BD, tests, riesgos y backlog. Diferenciar rigurosamente eventos ENTRANTES ajenos y eventos SALIENTES propiedad M5.
2. **Conservar el envelope Core de ocho campos:** `eventId,eventType,eventVersion,occurredAt,sourceModule,correlationId,causationId,data`. Sin `producer`,`subject`,`payload`,`targetModule` en los eventos M5.
3. **SALIENTES M5: CAMBIAR PRIMERO Y PASAR EL CONTRATO DESPUÉS.** El responsable decidió implementar en M5 el formato UUID propio, probarlo, documentar el payload común y entregarlo luego a todos los consumidores (`paymentRegistered` M4/M7, `paymentReversed` M4/M7, `debtSettled` M4/M7/M8, exenciones M1/M8). **No exigir ratificación de receptores para preparar el código local**, sí coordinar su adaptación y versionado antes de activar el envío real por Core. Un solo contrato por evento compartido, no payloads incompatibles por destino.
4. **ENTRANTES ajenos: NUNCA cambiar su contrato.** Aceptar y adaptar internamente el payload tal como lo publica cada módulo M1/M2/M4/M7/M8. Las PK Long M5 NO cambian; UUID de entidades propias M5 estables y persistidos para SALIDAS. IDs externos `originId`/`infractionId`/`ticketId`/`benefitId` se **copian exactamente del productor**; no adivinar UUID de ID numérico/código. M7 V4 `paymentId`/`debtId` están tipados `string`, pero esto no prohíbe preparar UUID textual propio en salidas M5.
5. **No inventar `citizenId` canónico M1**: si no existe correspondencia validada, aplazar eventos y no conceder descuentos sobre personas no vinculadas. Mantener `canonical_citizen_id` nulo hasta confirmación.
6. **No alterar** M2 v1.78, beneficios M8, reversión segura de cuotas ni V24/V25/V26 sin motivo, autorización y regresión. `debtSettled` MUNICIPAL_TAX sigue aplazado hasta contrato único.
7. **No confundir `PUBLISHED` de LocalLog con entrega real.** Mantener diferenciados aplazamiento, outbox y confirmación real de Core. No conectar Core/RabbitMQ hasta autorización explícita: fase final.
8. **Sin migraciones destructivas** ni escrituras en BD local `rentas` salvo aprobación explícita por operación. Cambios Flyway sólo versión nueva; pruebas en restauración aislada, respaldo y control de datos antes de autorización. No modificar scripts históricos.
9. **Idempotencia y negocio:** `eventId` estable al reintentar, dedupe consumidor, instantáneas inmutables para pagos/reversiones y reglas temporales; no generar eventos de reversión por solicitudes no ejecutadas; sin deuda por monto neto cero; sin descuentos sin regla autorizada.
10. **No commit, push, secretos o backups a Git** sin autorización. Nunca copiar `.env`, JWT, claves, `.dump`, logs sensibles, datos personales. Desarrollar en rama y usar PR.

## Política de pruebas

- Después de cada tarea: tests focalizados + suite completa backend `mvn test` desde `backend/` + suite frontend `npm test` desde `frontend/` cuando aplique, usando scripts/perfiles reales del repo. `git diff --check`.
- Revisar integridad PostgreSQL/Flyway sin modificar esquema productivo; si precisa cambios de BD, solicitar aprobación y probar en base aislada/restaurada.
- Añadir fixtures basados en **contratos originales correctos**, con diferencias documentadas frente a propuestas. Probar estados/nulos, UUID/cadenas, evento repetido/desordenado, permisos, montos/saldos, cuotas y reversiones, no regresión en otros consumidores.

## Formato de respuesta de Codex al terminar

1. Resumen exacto de cambios y alcance; 2. archivos y tests; 3. contratos y fuentes comparadas; 4. pendientes externos / ambigüedades; 5. migraciones propuestas/aplicadas y a qué BD; 6. pruebas ejecutadas y números reales; 7. riesgos y siguiente paso mínimo. Diferenciar **implementado local**, **aceptado por destino** y **entregado por Core**.

## Prioridad de trabajo

P0 verificar clon/commit; P1 M4; P2 acuerdo transversal `debtSettled`/IDs; P3 M1 identidad y recepción; P4 smoke tests M7/M8; P5 Core sólo al final; P6 E2E real entre portales. Si nuevos documentos ratificados alteran el plan, informar diferencias y versionar acuerdos.

## Regla adicional: formato definitivo de IDs salientes

**Leé `docs/handoff/06_UUID_SALIENTES_ACTUAL_VS_OBJETIVO.md` y `07_ENTRANTES_SALIENTES_CORE_Y_DISTRIBUCION.md`.** M5 implementa **PRIMERO** sus salidas a UUID persistentes propios, **DESPUÉS** les pasa el contrato actualizado a los consumidores (quienes adaptan sus entradas antes de activar Core). No modificar los contratos ENTRANTES ajenos. **SIEMPRE envelope Core inalterado** (`eventId,eventType,eventVersion,occurredAt,sourceModule,correlationId,causationId,data`). `originId` ajeno se conserva; `citizenId` canónico M1, no inventado. PK Long intactas. Migraciones PostgreSQL y conexión Core requieren aprobación separada.
