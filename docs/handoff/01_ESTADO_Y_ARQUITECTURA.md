> **Política vigente de arquitectura:** primero se normalizan **SALIDAS M5** a UUID persistentes propios y luego se distribuyen contratos actualizados a consumidores; las **ENTRADAS de otros módulos no se alteran** y **el envelope Core siempre conserva sus ocho campos**. Detalles `06_...` y `07_...`. Este archivo relata el estado histórico previo a ese cambio.

# Historial de implementación y decisiones técnicas — M5 Rentas

**Referencia temporal:** estado informado hasta 25/10/2026. Diagnóstico recibido de la ejecución local de Codex; no verificación contra el repositorio que el amigo vaya a clonar.

## A. Arquitectura general

- M5: backend **Java/Spring Boot**, frontend **React**, persistencia **PostgreSQL** (pruebas con H2 temporal y PostgreSQL 17), migración con **Flyway**.
- Mensajería objetivo: **M9 Core + RabbitMQ**, intercambio asíncrono con envelope común, publicación mediante **outbox**, registro de entrada, deduplicación por `eventId`, tolerancia a llegada duplicada o desordenada.
- Estado actual del transporte: `LocalLogEventPublisher`/adaptador en `IntegrationServices.java` **sólo deja un log** y puede marcar `PUBLISHED` local. No hay evidencia de publicación/aceptación real en Core. `DEFERRED`, `OUTBOX_PREPARED`, `NOT_CONFIRMED_BY_CORE` deben conservarse diferenciados.
- Las PK locales numéricas `Long` **se mantienen**. Se agregan identidades de integración UUID persistentes en las entidades M5 que las necesitan. **Nunca convertir DNIs/CUITs en UUID ni asignar el UUID de otro módulo.**

## B. M2 (Atención Ciudadana) — avance local cerrado

**Fuente:** `contratos_originales/M2_Eventos_v1.78_WIP.docx`.

- M5 consume `ticketUpdated` filtrado por `data.responsibleAreaId == "M5"`; ignora eventos administrativos `INFORMATION_REQUIRED`/`INFORMATION_PROVIDED` con `purpose=RECLASSIFICATION`; soporta `ROUTED`, `details.routing.category`/`subcategory`/`requestType`, `reassignment`, control de fechas, duplicados, estados terminales y eventos fuera de orden.
- M5 publica `updateTicketStatus` con seis variantes de contrato; `ticketId` es **el UUID original de M2**, mientras el PK local de `ticket_case` es `Long`.
- `updatedBy.id` de usuario humano es el UUID estable de `demo_user.integration_actor_id` obtenido de identidad confiable en backend; para acción automática `SYSTEM`/`null`. No usar username ni frontend.
- V24 aplicada a `rentas`: 7 empleados UUID, columnas `request_type`, `subcategory`, datos históricos conservados.
- Pendientes externos: M2 v1.78 WIP, entrega/acuse real, identidad canónica `citizenId` M1. No emitir `ticketCreated` desde Rentas: es M2→M1, no M2→M5.

## C. M8 (Desarrollo Social) — avance local cerrado, acuerdos pendientes

**Fuentes:** `contratos_originales/M8_Eventos_Desarrollo_Social.pdf` y borradores en `propuestas_no_ratificadas/`.

- M5 consume `socialBenefitUpdated` con `benefitId`, `citizenId`, `applicationId`, `programId`, `programName`, `benefits[]`, `status`, `reason`, `startDate`, `endDate`, `updatedAt`. Estados `APPROVED`, `REJECTED`, `SUSPENDED`, `FINALIZED`.
- Sólo `benefits[].type = TAX_EXEMPTION` modifica la materia tributaria; otros tipos sociales no generan descuentos.
- Hay reglas configurables de M5 por programa, concepto, porcentaje, período y aprobación/versionado. Si la regla está ausente/no aprobada o no existe identidad confiable M1, **no aplicar descuento**. Elegir el **mayor porcentaje** entre exenciones concurrentes del mismo concepto, sin sumar; no retroaplicar a liquidaciones históricas. Con 100% mantener liquidación/trazabilidad y **no crear deuda cobrable por monto cero**.
- V25: UUID propios M5 estables en deuda/liquidación/solicitud/exención. V26: modelos reglas, historial y trazabilidad. Ambas aplicadas localmente con respaldo previo. Usuarios/ciudadanos M1 no reciben UUID inventados.
- Outgoing: `overdueDebt` aplazado cuando falta UUID ciudadano, con posibilidad de reevaluación. `exemptionRequested`/`updateExemptionStatus` se concilian bajo acción de Supervisor por solicitud; primero solicitud, luego resolución, con idempotencia y sin inventar identidades. Eventos legados inválidos a `DEAD_LETTER` para revisión.
- `debtSettled` municipal **bloqueado/aplazado** hasta acuerdo único transversal. La salida real a Core sigue sin implementarse.

## D. M7 (Tránsito) — avance local para movimientos nuevos

**Fuente:** `contratos_originales/M7_Transito_Core_V4_2026-10-19.pdf`.

- M5 consume `infractionConfirmed`, conserva su `infractionId` UUID, usa `finalAmount` para crear obligación y deduplica; `debtorId` es DNI/CUIT **string**, no UUID.
- Pago directo y cuota nueva de plan: emiten `paymentRegistered` por cada imputación de capital/deuda; al saldo cero corresponde `debtSettled`; ejecución real de reversión → `paymentReversed` con importe original (no al solicitar/aprobar una reversión).
- La imputación por cuotas nuevas se registra transaccionalmente por deuda, para evitar recalcular distribuciones al revertir. Bloqueo de reversión si imputación no está ACTIVE, cuota no es PAID/PARTIALLY_PAID, plan no ACTIVE/COMPLETED, cuota CANCELLED o distribución histórica multideuda no verificable. Rechazos dejan auditoría sin modificar saldos/outbox; lock del plan previene carreras.
- `correlationId`: se recupera **del `infractionConfirmed` real** guardado en `integration_event_log.payload` mediante `ExternalObligation.sourceEventId`. Si falta/inconsistente, conservar pago/reversión pero **aplazar evento**; no fabricar correlación desde el `Long`.
- Conciliación de eventos económicos diferidos: instantánea estable de importe, saldo, fecha e IDs originales en `integration_event_log` para movimientos nuevos, reintentos idempotentes, preparación en secuencia solicitud/cancelación/reversión sin inferir saldos nuevos. Históricos sin instantánea: revisión manual.
- Imputación histórica N.º 3: $5.000 de capital de un plan de **una sola deuda** y sin origen M7; cuota CANCELLED, imputación ACTIVE. **No alterar, ni revertir por ruta insegura**. Cambios de seguridad implementados; la fila quedó intacta.
- Reporte final: 83 pruebas focalizadas + 394 backend completas aprobadas. No se hizo reinicio físico del backend para verificar persistencia bajo recuperación; sí se probó persistencia entre transacciones.
- `conceptCode` para M7 en M5 era `TRAFFIC_INFRACTION`, mientras ejemplo V4 usa `TRAFFIC_FINE`. V4 lo define **string**; falta confirmar semántica, no corregir basándose sólo en ejemplo.

## E. M4 (Habilitaciones) — siguiente módulo

**Fuente:** `contratos_originales/M4_Contrato_Octubre_2026_VERSION_DE_TRABAJO.docx`.

- `permitFeeGenerated` (M4→M5): id de tasa, solicitud, establecimiento, CUIT, `amount`, `currency`.
- `commercialFineGenerated` (M4→M5): id de multa, establecimiento, acta, CUIT, importe, moneda, motivo, fecha de decisión, referencia administrativa y referencias opcionales al origen externo.
- `originType`: `PERMIT_FEE` o `COMMERCIAL_FINE`. `originId` debe repetir **exactamente `data.id` M4**, no `externalRef` de la multa, ni UUID nuevo inventado en M5.
- M5 comparte con M4 `paymentRegistered`, `debtSettled`, `paymentReversed`. La documentación de M4 es una **versión de trabajo**, y falta aceptación de formato definitivo y de campos extra/nulos/IDs.
- Próxima tarea: diagnóstico sólo-lectura del código contra fuente M4, implementación local por etapas y pruebas de regresión M7/M8. No cambiar payloads compartidos unilateralmente.

## F. M1 (Ciudadanos) — dependencia transversal

**Fuente:** `contratos_originales/M1_Catalogo_Eventos_v2.1.docx` (v2.1 indica «Producción/Definitiva» dentro de M1, pero **no está alineado con los contratos inter-módulo recientes**).

- M1 documenta `citizenId`, `organizationId`, `representationId`, `user_id`, JWT `sub` como **numéricos/Long**; su envelope de eventos usa `producer`/`subject` y `eventId` en ejemplos como `M1-EVT-000123`, distinto al envelope acordado de Core.
- M2 entrega `citizenId` UUID; M8 y propuesta M5 requieren UUID canónico. **No existe aún una equivalencia oficial acreditada entre el ID numérico heredado y el UUID.** Los 3 contribuyentes existentes mantienen `canonical_citizen_id` nulo. M5 no debe inventar ni derivar UUID de DNI/CUIT/Long.
- M1 tiene login de ciudadano/empleado JWT HS256 de usuario; Core exige una **autenticación separada de módulo** con JWT RS256. No confundir ni reemplazar una por otra.
- M5 → M1 necesita confirmación formal de recepción de `exemptionRequested`, `updateExemptionStatus`, `paymentPlanRequested`, `updatePaymentPlanStatus`. El catálogo M1 v2.1 proporcionado **no documenta** esos cuatro consumos.
- Si llega una **respuesta más reciente de M1**, incorporarla a `DECISIONES_EXTERNAS.md`/contratos y reevaluar; este corte no la incluye.

## G. Flujo de datos y consistencia transaccional

1. Comando de negocio → validación de permisos/transición.
2. Cambio de entidad y auditoría en BD; el mismo acto debe dejar evidencia de imputación/estado.
3. Construcción de evento **una vez por hecho**, o `DEFERRED` si falta dependencia externa; persistencia mediante outbox/idempotency key.
4. Publisher local actual: sólo log; **no interpretar estado local como ack de Core**.
5. Cuando Core se conecte: entrega at-least-once, consumidor deduplica eventId, validación de `eventVersion`, fechas/causalidad, cola técnica independiente, reintentos seguros y DLQ; el orden de envío local no garantiza orden de recepción remota.
6. Persistencia materializada de cada módulo → portales reflejan el estado de **su propio backend**, no deben aparentar una operación remota exitosa basándose sólo en la acción originadora.

## H. Riesgos abiertos que no deben perderse

- **Mayor bloqueo transversal:** schema común de `debtSettled`, identidad canónica M1 y reglas por `originType` para consumidores M4/M7/M8.
- **Transporte no implementado:** Core/RabbitMQ; falta comprobar recepción, confirmaciones, DLQ, redelivery y actualización visible entre portales.
- **Posibles fallas de negocio:** conciliación de históricos sin instantánea, fuentes M1 no correlacionadas, reversiones indebidas de cuotas canceladas y eventos pendientes no recuperados.
- **Discrepancia versión:** M7 V4 define `eventId` UUID (no obliga explícitamente UUIDv4 en el receptor); Core/M5 productor usa UUIDv4. Validar contra Core en la fase de transporte sin suprimir validaciones generales sin explicación.
- **Seguridad:** ni logs públicos, repositorio ni eventos broadcast pueden incluir credenciales/tokens, `internalMessage` de ticketUpdated, PII innecesaria o backups.
