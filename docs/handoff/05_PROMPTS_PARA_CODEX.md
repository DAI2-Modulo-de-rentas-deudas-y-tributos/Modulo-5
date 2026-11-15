# Mensajes listos para que otro Codex retome M5 sin perder contexto

**Usar estos prompts en secuencia, NO pegarlos juntos ni autorizar todos los cambios a la vez.** Adjuntar el archivo de contrato original indicado. Leer antes `AGENTS.md` y los demás documentos.

## PROMPT 0 — primera conversación en el repositorio CLONADO

> Estoy continuando el TP municipal, Módulo 5 Rentas, desde el código que dejó otro desarrollador. Leé `AGENTS.md` y los documentos en `docs/handoff/` (00 a 07), en particular el estado, la matriz de eventos y el backlog. Contratá el código de la rama actual con lo documentado y no des por hecho que todos los cambios estaban subidos a GitHub. Indicá rama/último commit, archivos relevantes, migraciones V24–V26, estado de tests y discrepancias. No modifiques archivos, base de datos, contratos, RabbitMQ ni Git todavía. Proponé el primer cambio mínimo seguro.

## PROMPT 1 — diagnóstico de M4, próximo desarrollo

> Vamos con la integración M5 Rentas ↔ M4 Habilitaciones. Adjunté `contratos_originales/M4_Contrato_Octubre_2026_VERSION_DE_TRABAJO.docx`. Compará `permitFeeGenerated`, `commercialFineGenerated`, `paymentRegistered`, `debtSettled` y `paymentReversed` con el código real. Verificá id de tasa/multa, CUIT, moneda, origen, duplicación, pago parcial/total, cuotas y reversiones, correlationId y compatibilidad M7/M8. `originId` conserva M4 `data.id`, no `externalRef`. El documento M4 es versión de trabajo, no aceptación bilateral. No cambies nada aún: entregá matriz de gaps, archivos, riesgos, pruebas necesarias y cambios propuestos que no rompan contratos compartidos.

## PROMPT 2 — corrección M4 tras aprobar el diagnóstico

> Autorizo únicamente las correcciones locales M4 acordadas [describir alcance]. Reutilizá el camino económico transaccional existente en M5, deduplica eventos por eventId/origen, respeta `originId=data.id`, importes/saldos por imputación, no modifiques contratos ENTRANTES de M4/M7/M8 ni M2; los cambios a eventos SALIENTES M5 se preparan en la etapa siguiente conforme 06/07. Sumá fixtures basados en el doc M4 y regresión M7/M8. Si surge necesidad de migración o alterar contrato, detenete y proponela antes de implementarla. Ejecutá pruebas focalizadas y completas y reportá resultados reales. No commit/push, no Core.

## PROMPT 3 — compatibilidad transversal

> Auditemos el contrato único M5 compartido: `paymentRegistered` (M4,M7), `debtSettled` (M4,M7,M8), `paymentReversed` (M4,M7), `exemptionRequested` y `updateExemptionStatus` (M1,M8). Contrastá contratos originales, propuestas y JSON real del publisher; distinguí fecha/versiones/aceptación externa. Matriz por consumidor: required/optional, strings vs UUID, originType/originId, infractionId, citizenId, conceptCode, saldo, correlationId/causationId, null y filtering. Detectá incompatibilidades. Prepará primero el nuevo contrato único SALIENTE M5 a UUID propios (sin modificar entradas ajenas ni envelope), con plan de pruebas, y luego el paquete a distribuir a consumidores. En este prompt sólo entregá diagnóstico/propuesta; la implementación será autorizada aparte. Mantener MUNICIPAL_TAX aplazado si falta identidad canónica o datos válidos.


## PROMPT 3B — implementar UUID en eventos SALIENTES propios de M5 y preparar entrega a consumidores

> **Decisión definitiva:** M5 es dueño de sus eventos SALIENTES y **hacemos primero los cambios aquí**, luego pasamos los contratos a consumidores. NO modificamos contratos de eventos ENTRANTES de M1/M2/M4/M7/M8. El **envelope Core de ocho campos jamás cambia**. Auditá `debtId`, `paymentId`, `planId`, `requestId`, `exemptionId`, `debtIds[]`: reutilizar UUID persistentes V25 y preparar UUID de pago/plan donde falten, conservando PK `Long`. Ningún UUID nuevo por evento ni reemplazo de `originId` externo (M4 `data.id`, M7 `infractionId`), `ticketId` M2 o `citizenId` canónico M1. Proponé scripts nuevos sólo si necesarios y no los ejecutes en `rentas` sin aprobación. Actualizá DTOs/publishers M5 a un único JSON por evento compartido, fixtures y regresiones de M4/M7/M8/M1; prepará el contrato final JSON/Schema con campos/tipos/envelope/versiones, y un documento de entrega para cada consumidor. Registrá qué no pudo salir por falta de identidad y qué debe acordarse para la activación. **Todavía NO conectar Core ni afirmar aceptación externa, no hacer commit/push sin permiso.** Primero presentá impacto/migración y esperá autorización para cambios de BD.

## PROMPT 4 — M1 una vez recibida la respuesta del equipo

> Adjunté la respuesta nueva y ratificada de M1 [documento]. Contrastala con `contratos_originales/M1_Catalogo_Eventos_v2.1.docx` y nuestro código: UUID canónico `citizenId`, equivalencia con Long, organizaciones/representantes/empleados, formato envelope, receptores de `exemptionRequested`, `updateExemptionStatus`, `paymentPlanRequested`, `updatePaymentPlanStatus`. Prepará primero matriz de diferencias, migración mínima de correspondencias si hiciera falta y pruebas de reconciliación con M2/M8. No inventes equivalencias ni hagas migración sin autorización.

## PROMPT 5 — conexión Core sólo cuando los anteriores estén cerrados

> Ahora sí, con los contratos ratificados, revisá la especificación **actual** de M9/Core que adjunto. Planificá transporte real RabbitMQ de M5 manteniendo outbox/inbox, colas/suscripciones, token módulo, correlación, deduplicación y DLQ. El `LocalLogEventPublisher` no acredita entrega. Primero diagnóstico/plan y pruebas con entorno aislado; no conectar a producción ni modificar credenciales reales sin autorización. Asegurá que los consumidores no dependen del orden de llegada y que el portal distingue estado local de confirmación remota.

## PROMPT 6 — integración visible entre portales

> Ejecutemos pruebas E2E siguiendo `04_PLAN_DE_PRUEBAS_E2E.md`. Para cada flujo verificá operación en portal origen, evento real, recepción por Core, cambio de base del módulo destino y actualización visible del portal destino (incluido reload). Cubrí M2 tickets, M4 tasas/multas y pagos, M7 infracciones/planes/reversiones, M8 beneficios/exenciones y M1 identidad. Pruebas de duplicados, out-of-order, broker caído, restart, DLQ y concurrencia. Generá matriz con evidencia saneada, sin inventar que un flujo pasó si el portal destino no fue comprobado.

## Lo que siempre debe devolver Codex

**Hechos observados en repo**, decisiones, cambios efectuados, archivos, SQL propuesto/aplicado y base afectada, pruebas realmente ejecutadas y resultados, dependencias y siguiente tarea mínima. Nunca afirmar integración remota por tener tests unitarios verdes.
