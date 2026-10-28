# Traspaso unificado — M5 Rentas (actualizado con decisión salientes/entrantes/Core)

Documento Markdown para pasar a Codex en una sola pieza. Contratos completos y originales en el ZIP. **Decisión vigente:** modificar primero los eventos SALIENTES propiedad de M5 (UUID técnicos persistentes), después enviar contratos a sus consumidores; NO modificar los contratos de eventos ENTRANTES emitidos por otros módulos; SIEMPRE mantener el envelope de ocho campos acordado con Core. Migrations a base original, conexión real con Core y activación requieren autorización separada.

**Prioridad de lectura:** secciones `06_UUID_SALIENTES_ACTUAL_VS_OBJETIVO.md`, `07_ENTRANTES_SALIENTES_CORE_Y_DISTRIBUCION.md`, y `AGENTS.md`. Las descripciones de esquemas anteriores se conservan como **estado histórico**, no como prohibición de actualizar nuestras salidas. Este documento NO acredita un cambio en el repositorio del usuario ni aceptación remota.


---

<!-- INICIO 00_LEER_PRIMERO.md -->

# M5 RENTAS — Traspaso técnico para continuar el desarrollo

**Corte de información:** 25/10/2026. **Proyecto:** Desarrollo de Aplicaciones II / Sistema municipal integrado (M5 Rentas).
**Destinatarios:** nuevo desarrollador, su Codex y equipo de M5.
**Fuentes:** contratos proporcionados por los equipos y resultados de Codex compartidos por el responsable de M5. **Esta carpeta NO es una copia del repositorio ni una auditoría directa del código actual**: al clonarlo, confirmar rama, commits, estado de trabajo y rutas reales antes de editar.

**DECISIÓN PRIORITARIA DEL RESPONSABLE:** leer `06_UUID_SALIENTES_ACTUAL_VS_OBJETIVO.md` y `07_ENTRANTES_SALIENTES_CORE_Y_DISTRIBUCION.md`. **Primero actualizamos y probamos los eventos SALIENTES de M5 a UUID de entidades propias; después comunicamos los contratos a todos sus consumidores. NO alteramos los contratos de eventos ENTRANTES de otros módulos. El envelope de ocho campos acordado con Core permanece invariable.** Los formatos actuales `debt-<Long>`/`payment-<Long>` son transitorios, no el objetivo.

## 1. Instrucciones inmediatas de uso

1. Clonar el repositorio privado/compartido cuando el responsable lo publique; comprobar `git status`, rama y último commit. **Al 25/10 no se había hecho commit ni push de los cambios recientes**: es imprescindible que el responsable suba *todos* los cambios validados (incluidas migraciones y tests) antes de empezar. No se transfieren modificaciones que sólo existan en su máquina por el hecho de subir documentos.
2. Copiar esta carpeta a `docs/handoff/` dentro del repo; incorporar el `AGENTS.md` de esta entrega a la raíz **sólo si no existe uno**, o fusionar instrucciones cuidadosamente.
3. Leer `01_ESTADO_Y_ARQUITECTURA.md`, `02_CONTRATOS_DE_EVENTOS.md`, `03_BACKLOG_PRIORIZADO.md`, `04_PLAN_DE_PRUEBAS_E2E.md`, `06_UUID_SALIENTES_ACTUAL_VS_OBJETIVO.md` y `07_ENTRANTES_SALIENTES_CORE_Y_DISTRIBUCION.md`.
4. Considerar los archivos en `contratos_originales/` fuente primaria del módulo emisor/receptor **en la versión indicada**. `propuestas_no_ratificadas/` son **borradores**, no contratos aceptados.
5. No conectar RabbitMQ ni publicar eventos reales hasta terminar M4/M1, acordar `debtSettled` y recibir la configuración final del equipo Core; esta secuencia fue decidida por el equipo.

## 2. Vista ejecutiva

| Módulo | Implementación LOCAL en M5 al cierre | Lo que sigue sin probarse o confirmar |
|---|---|---|
| **M2 – Atención Ciudadana** | Contrato v1.78 implementado; Flyway V24 aplicado a `rentas`; UUID estable por empleado de prueba, ROUTED/reasignación/reclasificación, pruebas locales aprobadas | Conexión real a Core/M2; `citizenId` UUID canónico de M1; contrato v1.78 marcado WIP |
| **M8 – Desarrollo Social** | DTOs, consumidor `socialBenefitUpdated`, reglas de exención, liquidación, conciliación de solicitudes aplazadas; V25/V26 aplicadas, tests aprobados | UUID canónico M1; M8 debe ratificar UUID e `updateExemptionStatus/REJECTED`; `debtSettled` municipal aplazado; recepción real no probada |
| **M7 – Tránsito** | Consumo `infractionConfirmed`, pagos directos y por planes, reversiones seguras, correlación original, instantáneas para conciliación de eventos aplazados; 394 tests backend reportados aprobados | Contrato consumidor de pagos/IDs y `conceptCode`/`citizenId` acordados; pruebas reinicio físico; históricos sin instantánea; integración Core no probada |
| **M4 – Habilitaciones** | Integración existente con tasas y multas comerciales, preservación de `originId` M4, eventos de pago con contrato actual | **Próxima tarea:** auditar documento octubre, validar consumidores y corregir fallas locales sin afectar M7/M8; contrato M4 es versión de trabajo |
| **M1 – Ciudadanos** | Proyecciones preexistentes de contribuyentes/organizaciones; los eventos M1 conocidos manejan IDs numéricos | UUID canónico ciudadano, identidad organizacional y empleado; receptor de exenciones/planes; M1 v2.1 contradice UUID de M2; acuerdo aún no recibido en este corte |
| **M9 – Core** | Envelope Core y outbox local; adaptador de salida **escribe logs** | Autenticación de módulo, RabbitMQ, suscripciones, colas, entrega y ack efectivos, reintentos/DLQ y pruebas E2E entre portales: **fase final** |

**Regla esencial:** *suite verde ≠ aceptación entre módulos*. Un evento `PUBLISHED` en el adaptador actual **sólo indica escritura local de log**, nunca confirmación por Core.

## 3. Últimas pruebas y base de datos conocidas

- PostgreSQL local: base `rentas`, servidor **PostgreSQL 17.11**. **Flyway V22, V23, V24, V25 y V26 aplicadas con éxito** según reportes de Codex.
- **V24 M2:** agrega `demo_user.integration_actor_id UUID` único/estable; `ticket_case.request_type` y `ticket_case.subcategory`; conserva PK `Long`. 7 usuarios existentes migrados, 1 ticket histórico conservado.
- **V25 M8:** UUID persistentes de M5 para deuda, liquidación, solicitud y exención, sin modificar PK `Long`. En el corte: 6 deudas, 6 liquidaciones, 1 solicitud y 1 exención migradas; **3 ciudadanos sin `canonical_citizen_id`** por falta de correspondencia oficial con M1.
- **V26 M8:** reglas de programa/exención, historial, vigencias, trazabilidad y campos de beneficios. Tablas de reglas nuevas vacías en datos reales de `rentas`; funcionalidad verificada con pruebas.
- **Última ejecución reportada:** **394/394 pruebas completas backend** luego de la conciliación de M7; 83 focalizadas. **Frontend:** última suite completa reportada **314/314** (de etapa M8). No se declara una nueva ejecución posterior.
- No hubo migraciones después de V26; **las modificaciones posteriores de M7 son de código/tests y no necesariamente están subidas a Git**.
- Los respaldos `.dump` fueron guardados **fuera del repositorio**, no se incluyen aquí. Un clone nuevo no contiene registros reales ni respaldo local; cada integrante debe preparar su PostgreSQL según configuración segura/semillas del repo.

## 4. Rutas relevantes reportadas (verificar en el clon)

Backend: `backend/src/main/java/ar/gob/municipalidad/rentas/`.

- Modelos: `DomainEntities.java`, `FullStackEntities.java`.
- Liquidación/pagos/planes: `DomainServices.java`, `PaymentPlanPrincipalLedger.java`, `PlanWorkflowService.java`, `EventosDeDominio.java`, `OutboxEventosDeDominio.java`.
- Contratos entrantes: `integration/event/inbound/ConfirmedInboundEvents.java`.
- Contratos salientes: `integration/event/outbound/ConfirmedOutboundEvents.java`.
- Consumidores: `integration/consumer/ConfirmedIntegrationConsumers.java`, `integration/consumer/CoreM4Consumer.java`.
- Productor/outbox: `integration/producer/ConfirmedIntegrationOutbox.java`.
- Conciliaciones: `integration/producer/DeferredExemptionReconciliation.java`, `integration/producer/DeferredEconomicReconciliation.java`.
- Publisher de prueba (sin broker real): `IntegrationServices.java`.
- Repositorios: `Repositories.java`; docs: `backend/INTEGRATION_CONTRACTS.md`, `contracts/events/README.md`.
- Flyway PostgreSQL: `backend/src/main/resources/db/migration-postgresql/V24__m2_v1_78_identity_and_routing.sql`, `V25__m8_owned_integration_ids.sql`, `V26__m8_social_benefits_and_rules.sql`.
- Frontend: `frontend/src/pages/rentas/TicketsPage.jsx`, `frontend/src/services/rentasService.js`.

**Antes de correr el backend en otro equipo:** verificar `.env`/perfiles, secretos fuera de Git, versión de PostgreSQL, conexión y esquema en migraciones. No iniciar código nuevo contra una base anterior a V26 sin una estrategia de migración segura.

## 5. Compatibilidad ya protegida, pero no ratificada

- Un solo `paymentRegistered` y `paymentReversed` compartidos por **M4 y M7**; `debtSettled` por **M4, M7 y M8**; `exemptionRequested`/`updateExemptionStatus` por **M1 y M8**.
- Los IDs externos de **M4** (`data.id` de `permitFeeGenerated`/`commercialFineGenerated`) se devuelven tal cual en `originId`; **M7** exige `originId = infractionId` de origen.
- M7 Core V4 **define `paymentId` y `debtId` como strings**, no obliga UUID aunque muestre `PAY-...`/`DEBT-...`. En M5 los eventos económicos externos seguían emitiendo `payment-<Long>` y `debt-<Long>`; **la decisión es actualizarlos primero en las SALIDAS propias de M5** y entregar los contratos revisados a M4/M7/M8 para su adaptación antes de activar el envío por Core.
- **El M8 original contiene códigos como `c-00123` / `exr-00600`**, y las propuestas posteriores promueven UUID. No suponer que M8 ya aceptó el cambio. M1 v2.1 sigue usando números.
- `debtSettled` de `MUNICIPAL_TAX` **se deja aplazado** hasta acordar un schema único y políticas de filtrado/ruteo con M4/M7/M8.

## 6. Lista rápida para el responsable antes de subir a GitHub

- [ ] Verificar que el repo de la computadora del responsable incluye **todo** lo que reportó Codex: `git status --short`, `git diff --check`, archivos no versionados intencionales.
- [ ] Ejecutar `mvn test` desde `backend/` y `npm test` desde `frontend/`, según los comandos efectivos del proyecto.
- [ ] Confirmar que las migraciones V24–V26 están committeadas, y que no se retocaron después de ejecutarse localmente.
- [ ] Revisar `.gitignore`: `.env`, keys, tokens, bases/dumps, contraseñas, logs sensibles, caches, `node_modules`, `target`.
- [ ] Hacer commit coherente y push a una rama compartida; **hasta hacerlo, el amigo no recibirá los cambios recientes**.
- [ ] Dar acceso apropiado al colaborador en GitHub; que cree rama propia y PR; evitar push directo a `main` hasta revisión.
- [ ] Colocar este material en `docs/handoff/` y compartirlo con el otro Codex como contexto inicial.

## 7. Dónde seguir

- `01_ESTADO_Y_ARQUITECTURA.md`: historia de implementación, decisiones y límites.
- `02_CONTRATOS_DE_EVENTOS.md`: inventario completo y estructura de datos de los eventos M5, con fuentes y divergencias.
- `06_UUID_SALIENTES_ACTUAL_VS_OBJETIVO.md` y `07_ENTRANTES_SALIENTES_CORE_Y_DISTRIBUCION.md`: política **vigente** de cambios primero en salidas M5, entradas intactas, envelope Core invariable y distribución posterior a consumidores.
- `03_BACKLOG_PRIORIZADO.md`: instrucciones concretas para continuar por etapas.
- `04_PLAN_DE_PRUEBAS_E2E.md`: matriz de pruebas técnicas y entre portales.
- `AGENTS.md`: reglas operativas permanentes para Codex dentro del repositorio.
- `contratos_originales/`: fuente primaria adjunta (originales M1, M2, M4, M7 y M8).
- `propuestas_no_ratificadas/`: diseños históricos a contrastar, **no equivalen a aceptación externa**; el criterio de implementación actual está en `06_...` y `07_...`.


<!-- FIN 00_LEER_PRIMERO.md -->

---

<!-- INICIO 01_ESTADO_Y_ARQUITECTURA.md -->

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


<!-- FIN 01_ESTADO_Y_ARQUITECTURA.md -->

---

<!-- INICIO 02_CONTRATOS_DE_EVENTOS.md -->

# Contratos de eventos — M5 Rentas y módulos relacionados

**Versión de este inventario:** 25/10/2026. Leer junto con `contratos_originales/`. Este archivo **no reemplaza ni modifica** los contratos oficiales: extrae la interfaz que necesita Rentas, enumera la procedencia y señala incompatibilidades conocidas. **DECISIÓN POSTERIOR DEL RESPONSABLE:** sólo se actualizan los eventos que **M5 EMITE**, primero en código local y después se envía su contrato a consumidores; **los contratos que otros módulos NOS ENVÍAN no se cambian**. Envelope Core siempre inalterado. Ver `06_...` y `07_...`. Los JSON antiguos de §4.2 representan el **estado antes del objetivo UUID**, no el formato final a desarrollar.

## 0. Jerarquía de evidencia (OBLIGATORIA para Codex)

1. **Contrato original del emisor/receptor** con versión y fecha: fuente de los nombres, tipos y reglas **de esa versión**.
2. **Acuerdos explícitos posteriores ratificados por TODOS los consumidores afectados:** pueden ampliar/sustituir el original; solicitar evidencia/versionado.
3. **Decisión de salida M5:** desarrollar primero el objetivo UUID persistente en los eventos propios, probarlo, documentar su versión y distribuirlo luego a consumidores; **NO** equivale a aceptación externa ni habilita activación real prematura.
4. **Código/fixtures locales:** muestran lo implementado, **NO** prueban aceptación por otro módulo.
5. **Log del publicador local:** muestra preparación/escritura de log, **NO** entrega a Core.

**Fuentes en esta entrega:**
- `contratos_originales/M2_Eventos_v1.78_WIP.docx` — Atención Ciudadana, *WIP*; documento más reciente utilizado por M5.
- `contratos_originales/M7_Transito_Core_V4_2026-10-19.pdf` — Tránsito Core V4, vigente 19/10.
- `contratos_originales/M8_Eventos_Desarrollo_Social.pdf` — Desarrollo Social; ejemplos originales con IDs prefijados.
- `contratos_originales/M4_Contrato_Octubre_2026_VERSION_DE_TRABAJO.docx` — Habilitaciones, *versión de trabajo*.
- `contratos_originales/M1_Catalogo_Eventos_v2.1.docx` — Ciudadanos, *v2.1*, anterior a la confirmación de UUID Core requerida; sus ids siguen numéricos.
- `propuestas_no_ratificadas/M5_UUID_SALIENTES_OBJETIVO.md` y `M5_M8_UUID_PROPUESTA.md` — propuestas, **NO** aceptación externa.

## 1. Envelope (ocho campos) del ecosistema Core

```json
{
  "eventId": "8adf1264-d36f-4783-bd75-565978b6e3ac",
  "eventType": "nombreCamelCase",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-25T15:00:00-03:00",
  "sourceModule": "rentas",
  "correlationId": null,
  "causationId": null,
  "data": {}
}
```

- `eventId`: UUID único por hecho; para reintento del **mismo evento** mantener el mismo. Productor M5 genera UUIDv4. Un UUID de *evento* **no es** un UUID de *entidad*.
- `eventType`: nombre camelCase exacto; `eventVersion` actualmente `"1.0"` en flujo local; no aumentarla unilateralmente. `occurredAt`: fecha/hora ISO 8601 con offset.
- `sourceModule`: `ciudadanos` / `atencion-ciudadana` / `habilitaciones` / `rentas` / `transito` / `desarrollo-social` según emisor (otros slugs: `obras`, `ambiente`, `core`). No usar `M5` o `producer` arriba.
- `correlationId`: UUID del hilo, o null donde lo admita Core; **M7 V4 exige correlación en el evento de origen y respuestas con la misma**. En eventos derivados reutilizar correlación real, sin reconstruirla desde IDs Long.
- `causationId`: UUID del evento causal, o null si no hay causalidad. **Pendiente** de verificar con consumidores si necesitan un `causationId` no nulo para cadena pago/cancelación.
- `data`: únicamente campos de negocio; no `targetModule`, `payload`, `producer`, `subject` como extra envelope de M5.
- Entrega futura **at-least-once**, sin orden garantizado; consumidor deduplica `eventId` y maneja datos fuera de orden. Filtros/acks por tipo, área y origen.

## 2. Inventario completo que nos interesa

### ENTRANTES a M5

| Productor | Evento | Uso M5 | Estado del contrato |
|---|---|---|---|
| M1 | `citizenUpdated` | Proyección de persona/estado/domicilio | M1 v2.1 ID numérico, desalineado con UUID canónico |
| M1 | `organizationRegistered` | Organización/CUIT | ID org numérico, `taxId` CUIT string |
| M1 | `representationGranted` | Representante autorizado | IDs numéricos M1 |
| M1 | `representationExpired` | Fin de representación | IDs numéricos M1 |
| M2 | `ticketUpdated` | Tickets dirigidos a Rentas | v1.78 WIP; implementación local validada |
| M4 | `permitFeeGenerated` | Nueva tasa comercial | Octubre 2026, trabajo, ids M4 tipo `FEE-...` |
| M4 | `commercialFineGenerated` | Multa comercial | Octubre 2026, trabajo, ids `FINE-...` |
| M7 | `infractionConfirmed` | Genera deuda por infracción confirmada | Core V4, `infractionId` UUID |
| M8 | `socialBenefitUpdated` | Registrar/actualizar beneficio y aplicar reglas | PDF original + propuesta UUID no ratificada |

### SALIENTES desde M5

| Receptor(es) | Evento | Compartido / sensibilidad |
|---|---|---|
| M2 | `updateTicketStatus` | Contrato M2 v1.78; actualiza estado canónico en M2 |
| M1, M8 | `exemptionRequested` | Un solo evento; pendiente confirmar receptor M1 y tipo UUID M8 |
| M1, M8 | `updateExemptionStatus` | Un solo evento; variante REJECTED no ratificada por M8 |
| M1 | `paymentPlanRequested` | Propuesta M5; **no consta receptor confirmado** en M1 v2.1 |
| M1 | `updatePaymentPlanStatus` | Propuesta M5; **no consta receptor confirmado** en M1 v2.1 |
| M4, M7 | `paymentRegistered` | **Salida M5 a actualizar coherentemente a UUID propio; luego distribuir nuevo contrato a M4/M7** |
| M4, M7, M8 | `debtSettled` | **Salida M5 compartida: preparar formato UUID único y pasarlo a M4/M7/M8; MUNICIPAL_TAX aplazado mientras falten datos válidos** |
| M4, M7 | `paymentReversed` | **Salida M5 a actualizar coherentemente a UUID propio; luego distribuir contrato a M4/M7** |
| M8 | `overdueDebt` | Necesita identidad canónica; aplazar sin ella |

`ticketCreated` existe como M2 → M1, **NO** se consume en la integración oficial M2 → M5.

## 3. Contratos ENTRANTES: detalles por evento

### 3.1 M1 → M5 (fuente: M1 v2.1)

- **`citizenUpdated`**: `data.citizenId` numérico (**contradice objetivo UUID**), `updateType = REGISTERED | BLOCKED | DECEASED | ADDRESS_UPDATED`, `details` según tipo. `REGISTERED` puede incluir `dni`, `cuil`, `name`, `lastname`, `birthdate`, `state`, `nationality`, `address{propertyId,street,number,primary,effectiveFrom}`; otros estados incluyen motivo, fallecimiento o cambio domicilio. `updatedBy` figura en ejemplos como objeto con ID numérico M1.
- **`organizationRegistered`**: ejemplos `organizationId: 3311` numérico, `cuit: [CUIT DE EJEMPLO REDACTADO]` numérico, `taxId: "[CUIT DE EJEMPLO REDACTADO]"` CUIT string real, `legalName`, `tradeName`, `type`, `status`, `holder:{personId,personType}`. **No confundir el ID org interno con CUIT**. M4 publica `cuit` y M5 debe buscar correspondencia legítima (no generar identidad M1 nueva).
- **`representationGranted`**: `representationId`, `personId`, `organizationId`, `cuit`, `scope`, `from`, `status=VALID`. IDs numéricos en ejemplos de M1.
- **`representationExpired`**: mismos vínculos relevantes, `until`, `status=EXPIRED`.
- **CONFLICTO ENVELOPE:** M1 v2.1 documenta `producer`, `subject` y `eventId` tipo `M1-EVT-...` en ejemplos. No asumirse Core-compliant; coordinar corrección con M1. **El estado de integración no queda resuelto por convertir números a strings en Rentas.**

### 3.2 M2 → M5: `ticketUpdated` (fuente: M2 v1.78 WIP)

Campos comunes de `data`: `ticketId` UUID; `publicId` string humano; `citizenId` UUID o null (`isAnonymous=true` obliga null); `isAnonymous` boolean; `responsibleAreaId` string; `updateType` enum; `currentStatus`/`currentPriority`; opcionales `progress` entero 0..100, `publicMessage`, `details`, `attachments[]`; `updatedAt` ISO con offset.

- Consumidor **sólo actúa cuando** `responsibleAreaId="M5"` y el tipo amerita gestión. Ignorar en operación `details.informationRequest.purpose=RECLASSIFICATION` y `details.informationResponse.purpose=RECLASSIFICATION`, incluso si área M5. Acknowledge sin guardar contenido de negocio de tickets no dirigidos a M5.
- `ROUTED` contiene **snapshot completo** `details.routing` con **requeridos** `requestType`, `category`, `subcategory`, `ticketType` (`COMPLAINT|REQUEST|INQUIRY|SUGGESTION`), `summary`, `description`; opcionales `formData`, `location`, `resolutionDueAt`, `escalation`, `reassignment`. Si `reassignment=true`, actualizar mismo `ticketId`, **no duplicar**.
- Tipos de update en M2: `STATUS_CHANGED`, `ROUTED`, `PROGRESS`, `PRIORITY_CHANGED`, `ESCALATION_CHANGED`, `INFORMATION_REQUIRED`, `INFORMATION_PROVIDED`, `DUPLICATE_LINKED`, `REOPENED`, `RESOLVED`, `CLOSED`, `CANCELLED`, `CONTENT_UPDATED`.
- `INFORMATION_PROVIDED` debe incluir `details.informationResponse.message` no vacío o attachments no vacíos. `DUPLICATE_LINKED`: `details.duplicate.mainTicketId` UUID.
- Nunca incluir `internalMessage` en un `ticketUpdated` broadcast.

### 3.3 M4 → M5: tasas y multas (fuente: M4 octubre, *trabajo*)

- **`permitFeeGenerated`**: `data={id,permitApplicationId,establishmentId,cuit,amount,currency}`. `id` puede lucir `FEE-001` (string M4), CUIT es un documento de 11 dígitos, `amount` número y `currency` como `ARS`. Debe crear UNA obligación por origen; deduplicar por `eventId` **y por identidad de origen** cuando corresponda.
- **`commercialFineGenerated`**: `data={id,establishmentId,actId,cuit,amount,currency,reason,decidedAt,externalRef,sourceViolationId?,sourceModule?}`. `id` tipo `FINE-001`, **distinto** de `externalRef` (número de disposición). Campos de origen de M6 son condicionales. Crear obligación por multa sin duplicar.
- **Para respuestas M5** usar `originType=PERMIT_FEE|COMMERCIAL_FINE`, `originId=data.id` **exacto**; nunca cambiar a una UUID inventada por M5. Campos exactos M4 consumo de pagos/cancelaciones/reversiones en §4/§5 abajo.

### 3.4 M7 → M5: `infractionConfirmed` (fuente: M7 Core V4, 19/10)

`data={infractionId:UUID,debtorId:string,debtorIdType:"DNI"|"CUIT",licensePlate:string,infractionType:string(enum pendiente catálogo),infractionDateTime:datetime,baseAmount:number,aggravatingFactors:array,finalAmount:number,inspectorId:string,location:{street,crossStreet,latitude,longitude}}`.

- `infractionId`: UUID propiedad de M7 y origen real de las respuestas. `debtorId` **DNI/CUIT STRING**, no UUID, aunque se deba correlacionar con M1 mediante índice verificable.
- Crear obligación con `finalAmount`, deduplicar infracción, preservar correlación original válida en su log de entrada. Envelope M7 V4 exige `correlationId`.

### 3.5 M8 → M5: `socialBenefitUpdated` (fuente: M8 PDF)

`data={benefitId,citizenId,applicationId,programId,programName,benefits:[{type,amount?}],status,reason,startDate,endDate,updatedAt}`.

- Tipos `benefits[].type`: `TAX_EXEMPTION`, `FOOD_ASSISTANCE`, `UTILITY_SUBSIDY`. Sólo el primero puede afectar liquidación tributaria; M5 configura la regla `programId→conceptCode/percentage/validity`, **no lo obtiene del nombre**.
- Estados: `APPROVED` (reason null, fechas vigencia); `REJECTED` (reason, fechas null); `SUSPENDED` (reason, fechas null); `FINALIZED` (reason, `startDate` null, `endDate` puede informar fin). Todos conservan el mismo `benefitId`.
- **El PDF original usa** `ben-00789`, `c-00123`, `sol-00456`, `prog-00789` como strings. La propuesta posterior exige UUID; **pendiente ratificación M8**. M5 debe registrar lo que efectivamente emita M8 según contrato aceptado, no inventar IDs del módulo ajeno.
- Si el ciudadano no puede vincularse autoritativamente con M1, proyección pendiente; sin descuento. Eventos fuera de orden no sobrescriben estado nuevo.

## 4. Contratos SALIENTES: payloads y condiciones

Todos los ejemplos a continuación muestran **la forma de `data`**. El envelope del §1 envuelve cada `data` con `eventType` y `sourceModule="rentas"`. Valores `{…}` representan un registro existente y confirmado, no un valor literal para publicar.

### 4.1 M5 → M2: `updateTicketStatus` (M2 v1.78)

**Campos de `data`:** `ticketId` UUID de M2, `updateType`, `publicMessage?`, `internalMessage?`, `progress?` (0..100), `details?`, `attachments? [{fileName,contentType,url,sizeBytes?}]`, `updatedBy:{type:"EXTERNAL_USER",id:UUID real | type:"SYSTEM",id:null}`, `updateOccurredAt` ISO con offset.

| `updateType` | Condición de negocio / detalles |
|---|---|
| `STARTED` | Iniciar gestión de ticket ROUTED; cambia a IN_PROGRESS en M2 |
| `PROGRESS` | Avance en IN_PROGRESS; debe aportar progreso/mensaje/adjunto útil; STARTED previamente si hace falta |
| `INFORMATION_REQUIRED` | `details.informationRequest.messageForCitizen` obligatorio; `requiredBy?`; máximo una solicitud activa |
| `RETURNED` | `details.returnInfo.reasonCode`: `INVALID_INFORMATION` / `REQUEST_TYPE_MISMATCH` / `INSUFFICIENT_CONTEXT` / `OTHER`; mensaje público o interno requerido |
| `RESOLVED` | `details.resolution.type`: `ACTION_COMPLETED` / `REQUEST_FULFILLED` / `INQUIRY_ANSWERED` / `ACKNOWLEDGED` / `NO_FURTHER_ACTION_REQUIRED`; `publicMessage` no vacío |
| `REJECTED` | `details.cancellation.reasonCode`: `OUT_OF_SCOPE` / `DOES_NOT_APPLY` / `INVALID_REQUEST_TYPE` / `INVALID_DATA` / `REJECTED_BY_AREA` / `OTHER`; mensaje público o interno |

No enviar `newStatus`, `previousStatus` o `responsibleAreaId` dentro de este `data` salvo contrato posterior. M2 es dueño del estado final; no publica espejo `ticketUpdated` por cada acción M5.

### 4.2 Eventos económicos compartidos M5 → M4 / M7 / M8

**`paymentRegistered`** (M4,M7):

```json
{"paymentId":"payment-123","debtId":"debt-456","originType":"TRAFFIC_FINE","originId":"93fd4382-2343-4f3f-a127-9328b29fc520","infractionId":"93fd4382-2343-4f3f-a127-9328b29fc520","amountApplied":50000,"paidAt":"2026-10-25T10:00:00-03:00","remainingBalance":25000}
```

**`debtSettled`** (M4,M7,M8):

```json
{"debtId":"debt-456","citizenId":null,"conceptCode":"TRAFFIC_INFRACTION","originType":"TRAFFIC_FINE","originId":"93fd4382-2343-4f3f-a127-9328b29fc520","infractionId":"93fd4382-2343-4f3f-a127-9328b29fc520","settledAt":"2026-10-25T11:00:00-03:00","remainingBalance":0,"outstandingBalance":0}
```

**`paymentReversed`** (M4,M7):

```json
{"paymentId":"payment-123","debtId":"debt-456","originType":"TRAFFIC_FINE","originId":"93fd4382-2343-4f3f-a127-9328b29fc520","infractionId":"93fd4382-2343-4f3f-a127-9328b29fc520","reversedAmount":50000,"reversedAt":"2026-10-25T12:00:00-03:00","remainingBalance":75000,"reason":"Reversion autorizada"}
```

**ATENCIÓN — JSON DE ESTADO ANTERIOR:** estos tres JSON con `payment-123` / `debt-456` son **ejemplos didácticos de la forma anterior**, NO eventos capturados ni el contrato de salida final deseado. El último supone deuda original 75.000, pago 50.000 y reversión íntegra posterior sin más movimientos; monto/saldo deben venir del ledger real. Aunque M7 V4 tipa `paymentId`/`debtId` como **STRING**, la decisión de M5 es **implementar primero UUID persistentes en sus salidas y luego entregar el nuevo contrato a M4/M7/M8**, con adaptación/versionado posterior. **No cambiar las entradas** de M4/M7 ni el envelope Core. Ver ejemplo objetivo completo en `06_UUID_SALIENTES_ACTUAL_VS_OBJETIVO.md`.

| `originType` | `originId` | `infractionId` | Receptores / cautelas |
|---|---|---|---|
| `PERMIT_FEE` | **M4 `permitFeeGenerated.data.id`** intacto | **Omitido** | M4; el nombre/campos con `citizenId` extra requieren confirmación M4 |
| `COMMERCIAL_FINE` | **M4 `commercialFineGenerated.data.id`** intacto | **Omitido** | M4; no usar `externalRef` como origen |
| `TRAFFIC_FINE` | **M7 `infractionId`** UUID original | **Igual a `originId`**, requerido por V4 | M7; preservar correlación de infracción; `conceptCode` pendiente de acuerdo |
| `MUNICIPAL_TAX` | Propuesta: UUID de liquidación **propia** de M5 | Omitido | M8; `debtSettled` **aplazado** hasta contrato único acordado |

- `paymentRegistered`: por **imputación** de pago a deuda (`amountApplied` y saldo de esa deuda), no importe agregado de caja si hay varias deudas.
- `debtSettled`: únicamente al saldo cero, `remainingBalance=0` y `outstandingBalance=0`; no disparar cancelación por pago parcial.
- `paymentReversed`: cuando reversión efectivamente **ejecutada**, por imputación original de deuda; preservar importe/saldo de la instantánea del hecho, no recalcular desde saldo actual.
- Correlación: para deuda M7 conservar `correlationId` de `infractionConfirmed`; si falta verificar fuente, **DEFERRED**. Para M4 confirmar política original con su equipo; no inferir que M7 define la política de M4.
- `citizenId` de `debtSettled`: M7 lo considera `string|null` opcional; M8 lo ejemplifica como ID ciudadano de texto en su PDF y la propuesta usa UUID; M4 documento de trabajo omite ese campo. **No completar DNI ni Long como si fueran el UUID canónico.**
- Evento `debtSettled` municipal bloqueado hasta acuerdo único sobre `debtId`, `originId`, `citizenId`, nulos, `conceptCode`, otros campos y filtrado de consumidores. No introducir sub-eventos por módulo.

### 4.3 M5 → M8: `overdueDebt`

`data={debtId,citizenId,conceptCode,outstandingAmount,dueDate}`. Ejemplo de estructura:

```json
{"debtId":"503abb3c-f73c-4295-ab86-4fae0fe847b8","citizenId":"2575b9ce-65b8-449f-873b-62ac40328948","conceptCode":"TASA_SERVICIOS","outstandingAmount":85000,"dueDate":"2026-09-20"}
```

Este ejemplo **representa la propuesta UUID**. En producción sólo `debtId` UUID *real persistente M5* y `citizenId` UUID *real canónico M1*; sin este último, conservar deuda y registro aplazado, no evento inválido.

### 4.4 M5 → M1 y M8: `exemptionRequested`

`data={requestId,citizenId,conceptCode,reason,requestedPercentage,requestedFrom,requestedUntil}`.

```json
{"requestId":"034c24df-c322-484f-9d23-489343563dba","citizenId":"2575b9ce-65b8-449f-873b-62ac40328948","conceptCode":"TASA_SERVICIOS","reason":"Situacion socioeconomica","requestedPercentage":50,"requestedFrom":"2026-11-01","requestedUntil":"2027-08-31"}
```

`requestId` UUID persistente M5 (V25), `citizenId` canónico M1. **M8 oficial ejemplifica** `exr-00600` en lugar de UUID; propuesta UUID no ratificada. M1 v2.1 no confirma consumo. Se difiere y luego concilia por Supervisor; no repetir publicaciones.

### 4.5 M5 → M1 y M8: `updateExemptionStatus`

**`APPROVED`**: `data={requestId,citizenId,status:"APPROVED",exemptionId,conceptCode,percentage,validFrom,validUntil}`. **`REJECTED`** propuesto: `{requestId,citizenId,status:"REJECTED",conceptCode,reason}`; no inventar `exemptionId` ni porcentajes de aprobación al rechazar.

- `requestId`: exactamente el mismo que `exemptionRequested`; `exemptionId` UUID persistente M5 si se aprobó. Misma correlación del flujo. Outbox prepara primero solicitud, luego resolución; esto no garantiza llegada ordenada remota.
- **M8 PDF solo ejemplifica APPROVED**; `REJECTED` debe ratificarse. M1 no documenta consumidor en v2.1.

### 4.6 M5 → M1: `paymentPlanRequested`

**Propuesta M5**, no contrato receptor ratificado en M1 v2.1: `data={requestId:UUID M5,citizenId:UUID M1,debtIds:[UUID de deudas M5],totalDebt:number,installments:int}`. Confirmar que el receptor quiera UUID y qué campos/estados requiere.

### 4.7 M5 → M1: `updatePaymentPlanStatus`

**Propuesta M5**, no contrato receptor ratificado en M1 v2.1: `GRANTED`: `{requestId,citizenId,status:"GRANTED",planId,installments,totalAmount}`; `REJECTED`: `{requestId,citizenId,status:"REJECTED",reason}`. `requestId` reutilizado de solicitud. `planId` debe representar plan M5 estable y no UUID nuevo por evento. **No declarar listo sin validar si actualmente existe este UUID persistente de plan**: V25 reportó deuda/liquidación/solicitud/exención, no uno nuevo de plan.

## 5. Estado de aceptación externa: NO inventar acuerdos

| Acuerdo | Evidencia conocida | Decisión de trabajo |
|---|---|---|
| M2 ticket v1.78 | Documento original WIP y pruebas locales | Implementación local, entrega real pendiente |
| M7 V4 pago y reversión | `paymentId`/`debtId` `string`, `originId` e `infractionId` string en respuesta; identidad externa UUID original | Mantener compatibilidad V4; `conceptCode`, `citizenId` y validación bilateral pendientes |
| M4 octubre | Documento «versión de trabajo» | Consumidor real/aceptación de extras y nulos pendiente |
| M8 desarrollo social | PDF con IDs prefijados y sólo status de exención APPROVED | Propuesta UUID y REJECTED pendiente M8 |
| M1 v2.1 | IDs numéricos + envelope distinto; no lista de recepción de exenciones/planes M5 | Resolver identidad canónica/receptores; no inventar mappings |
| Core | Envelope/referencias técnicas; sin transporte real | Conexión en fase final; verificar su spec actual antes de codificar |

## 6. Requisitos de pruebas contractuales mínimos

- Por evento entrante: serializar/deserializar **fixture conforme a original**; payload inválido no produce efecto; `eventId` duplicado no produce doble modificación; otro `eventId` sobre mismo objeto no duplica por identidad de dominio; fuera de orden no pisa estado reciente.
- Por evento saliente: comparar `data`, tipos, required, `null`/omisiones, fechas/moneda/saldos, `correlationId` original y `eventId` estable en reintento.
- Matriz de origen de 3 eventos económicos: `PERMIT_FEE`, `COMMERCIAL_FINE`, `TRAFFIC_FINE` y `MUNICIPAL_TAX` cuando se ratifique. `infractionId` sólo para tráfico.
- `exemptionRequested`→`updateExemptionStatus`: mismo `requestId`, una sola vez, sin estado falsamente entregado; `REJECTED` no inventa `exemptionId`.
- Integración cruzada M2/M7/M8 existente: suite completa backend/frontend antes y después de cualquier cambio en DTO/outbox/pagos.


<!-- FIN 02_CONTRATOS_DE_EVENTOS.md -->

---

<!-- INICIO 03_BACKLOG_PRIORIZADO.md -->

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


<!-- FIN 03_BACKLOG_PRIORIZADO.md -->

---

<!-- INICIO 04_PLAN_DE_PRUEBAS_E2E.md -->

> **Aclaración de secuencia:** antes de activar Core, los eventos **salientes M5** se actualizan a UUID propios, se prueban y se entrega su contrato a cada consumidor. En las pruebas E2E hay que verificar **los mensajes entrantes tal como son emitidos por M1/M2/M4/M7/M8** y **el envelope Core inalterado**.

# Plan de calidad, pruebas E2E y sincronización visible entre portales

**Propósito:** demostrar que el sistema municipal funciona como ecosistema, no sólo que M5 genera JSON válidos. Ejecutar cuando Core y los servicios de M1/M2/M4/M7/M8 estén desplegados en un **entorno de pruebas acordado**. Hasta entonces, mantener fixtures y pruebas contractuales locales.

## 1. Cinco niveles de prueba (no son equivalentes)

1. **Unidad/dominio:** cálculo, estado, autorizaciones y no duplicación (mvn test; Jest/Vitest del frontend conforme scripts reales).
2. **Integración con PostgreSQL:** migrations V1–V26, escritura transaccional de ledger/outbox, concurrencia, consultas/read models, estados persistentes.
3. **Contrato/fixtures:** nombre/version, envelope, payload requerido, enums, UUID o strings según versión ratificada, null/ausencias, importes/fechas; cotejar por consumidor.
4. **Integración real de mensajería:** entrega a Core/broker, ack del destino, redelivery, DLQ, claves idempotencia, correlación y sin orden garantizado.
5. **E2E entre portales:** acción en portal A → operación backend → evento → estado backend B → información visible en portal B después de sincronización/refresh, sin mentir sobre «enviado»/«pagado».

## 2. Matriz E2E mínima

| ID | Acciones de usuario | Evento / efectos esperados | Evidencia obligatoria |
|---|---|---|---|
| E2E-01 | Crear ticket identificado en M2 y derivarlo a M5 | `ticketUpdated/ROUTED`: el mismo `ticketId` aparece en la bandeja de Rentas, categoría/subcategoría correctas | Logs de M2/Core/M5, BD y captura de ambos portales |
| E2E-02 | Empleado M5 inicia y resuelve ticket | `updateTicketStatus/STARTED` y `RESOLVED`: M2 refleja estado canónico, mensaje público visible; `updatedBy.id` UUID real M5 | Evento y respuesta M2; vistas tras reload |
| E2E-03 | M2 solicita reclasificación `RECLASSIFICATION` y redirige | M5 ignora eventos administrativos; ROUTED con `reassignment=true` actualiza ticket existente sin duplicarlo | Una sola fila/ticket en M5, estados correctos en ambos portales |
| E2E-04 | M4 genera tasa de habilitación | `permitFeeGenerated`: Rentas crea una obligación única, CUIT y monto correctos | `data.id` M4 preservado y obligación en Rentas |
| E2E-05 | M4 genera multa comercial | `commercialFineGenerated`: M5 crea deuda de origen `COMMERCIAL_FINE`; referencia administrativa no sustituye originId | Registro en portales M4/M5, referencia idéntica |
| E2E-06 | Rentas recibe pago parcial de tasa/multa M4 | `paymentRegistered`: M4 refleja pago parcial y saldo restante correcto, no lo marca cancelado | Imputación por deuda, mensaje/ack y UI M4 |
| E2E-07 | Rentas cancela por completo tasa/multa M4 | `paymentRegistered` + `debtSettled`; M4 marca obligación saldada | Saldos 0 en M5/M4, no duplicación |
| E2E-08 | Revertir pago ejecutado sobre obligación M4 | `paymentReversed`: saldo de M4 se recompone, sin perder traza | Ledger M5, evento recibido M4, ambas pantallas |
| E2E-09 | M7 confirma infracción | `infractionConfirmed`: se crea **una** deuda M5 por `infractionId` UUID original y finalAmount | Infracción en M7, deuda de tránsito en M5 |
| E2E-10 | Pagar multa M7 parcialmente | `paymentRegistered`, M7 conserva originId=infractionId y ve saldo correcto | Core ack, cambios en portal M7 |
| E2E-11 | Cancelar total multa M7 | `debtSettled`, M7 indica cancelación y 0 pendiente | Comparación de IDs/saldos/estados en ambos portales |
| E2E-12 | Pagar multa M7 mediante cuota de plan | Misma imputación por deuda que pago directo; si salda, `debtSettled` | Capital distribuido, snapshots, UI tras reload |
| E2E-13 | Revertir cuota de plan permitida | `paymentReversed` por deuda con importe original exacto; M7 actualiza saldo | Ledger/auditoría/outbox/UI; sin recalcular proporciones |
| E2E-14 | Intentar revertir cuota CANCELLED | Error de negocio, cero variación de saldo/cuota/eventos | Captura de error y hashes de datos estables |
| E2E-15 | M8 aprueba beneficio de exención social | `socialBenefitUpdated/APPROVED`: Rentas vincula ciudadano **canónico** y regla autorizada por programa, crea descuento sobre liquidaciones nuevas elegibles | IDs M1/M8 iguales, regla auditada, cálculo en UI M5 |
| E2E-16 | M8 suspende y luego finaliza beneficio | `SUSPENDED`/`FINALIZED`: futuras liquidaciones respetan vigencia; históricas no se reescriben | Fechas, descuentos antes/después, historial |
| E2E-17 | Crear solicitud exención en Rentas | `exemptionRequested`: M1 y M8 ven misma solicitud con `requestId`, `citizenId` y porcentaje correcto **si confirman ser receptores** | Copia evento y UI/perfil cuando exista soporte |
| E2E-18 | Aprobar/rechazar exención de Rentas | `updateExemptionStatus`: estado consistente en M1/M8, `REJECTED` sin exemptionId inventado | Contrato ratificado y vistas de 3 portales |
| E2E-19 | Cancelar deuda MUNICIPAL_TAX | `debtSettled` único, filtrado correcto M8/M4/M7 según acuerdo, UI M8 actualizada | Sólo cuando contrato transversal esté acordado |
| E2E-20 | Actualizar/bloquear ciudadano en M1 | `citizenUpdated`: M5 refleja vínculo y restricciones pertinentes sin mezclar IDs numéricos/UUID | Proyección canónica, acceso y vista usuario |
| E2E-21 | Solicitar/aprobar/rechazar plan de pagos M5 | Eventos `paymentPlanRequested` / `updatePaymentPlanStatus` proyectados en M1 **si M1 confirma recepción** | Flujo real con IDs y estados compartidos |

## 3. Fallos/resiliencia obligatorios

| Prueba | Procedimiento | Aceptación |
|---|---|---|
| Duplicado de evento | Entregar mismo `eventId` dos veces | Una sola obligación/ticket/beneficio y un solo efecto |
| Redelivery de publisher | Cortar confirmación y reintentar | Mismo `eventId` para un hecho ya construido; consumidor deduplica |
| Eventos fuera de orden | Resolver antes de recibir un PROGRESS antiguo; suspender antes de recibir APPROVED viejo | No retroceder estado ni duplicar acciones; política contractual documentada |
| Broker/Core temporalmente caído | Ejecutar pago/solicitud y reiniciar backend | Operación local persiste; evento queda pendiente, no se declara entregado; reintento eventual seguro |
| Reinicio **físico** | Parar proceso con outbox/DEFERRED y volver a iniciar | Evento sigue disponible, sin regenerar IDs ni montos |
| DLQ/error estructural | Inyectar payload imposible/versión no soportada | No aplicar cambio, trazabilidad, alerta, política de DLQ documentada |
| Correlación ausente | Retirar fuente `infractionConfirmed` o simular falta sin tocar producción | Pago se conserva, evento se aplaza, no fabricar correlationId |
| Fuente canónica ausente | Beneficio `citizenId` sin correspondencia M1 | Beneficio pendiente, no descuento a otro ciudadano |
| Pago dividido | Distribuir un pago entre 2+ deudas | Un `paymentRegistered` por imputación, montos suman pago, saldos correctos |
| Reversión parcial/concurrente | Doble petición o estado de cuota cambiando | Operación idempotente/serializada, sin reversión doble |
| UI eventual consistency | Deshabilitar comunicación unos instantes | UI no informa «confirmado en destino» antes de ack/proyección; refresco posterior correcto |

## 4. Formato de evidencia por prueba

Guardar como Markdown/CSV **sin datos personales reales**:

```text
Test ID:
Fecha y entorno:
Contrato/versión:
Módulos origen y destino:
Usuario de prueba (ID sintético):
Acción UI y datos de entrada:
ID de evento / correlación (de prueba):
Publicación local / aceptación Core / consumo remoto:
Estado BD origen antes→después:
Estado BD destino antes→después:
Estado visible portal origen/destino:
Resultado esperado / observado:
Capturas o logs sanitizados:
Estado PASA/FALLA/BLOQUEADO y enlace a issue:
```

## 5. Gates de aprobación

- **Antes de conectar Core:** contratos finales con versión, identidades y `originType` acordados; suites locales verdes.
- **Antes de prueba interportal:** despliegue accesible, credenciales de prueba, configuración de colas/ruteo, observabilidad/ack, datos sintéticos coherentes M1/M4/M7/M8.
- **Antes de presentar TP:** cada flujo crítico con evidencia de actualización visible entre portales, estados consistentes, tests de fallas principales, discrepancias documentadas y sin bloqueos ocultos.

## 6. Preguntas necesarias a los otros equipos (llevar a una reunión)

- **M1:** ¿cuál es UUID canónico ciudadano y equivalencia Long? ¿Qué cuatro eventos salientes M5 consume M1? ¿Cuándo está actualizado el envelope?
- **M4:** ¿qué formato/versiones de pagos/cancelaciones/reversiones acepta? ¿Campo `citizenId` extra? ¿`originId` refleja id de tasa/multa como propone doc octubre? ¿Correlación requerida?
- **M7:** ¿`conceptCode` es código de tributo libre o debe ser `TRAFFIC_FINE`? ¿Qué `citizenId` espera? ¿Reintentos/reversiones/idempotencia? ¿`debtId`/`paymentId` se conservan string?
- **M8:** ¿ratifica UUID `benefitId`/`applicationId`/`programId` y `requestId`? ¿`REJECTED` status de exención? ¿Filtra debtSettled por `MUNICIPAL_TAX` o consume todos?
- **Core:** ¿interfaz y contrato efectivos de publicación/recepción, JWT módulo, cola, routing y filtros? ¿ack de broker vs aceptación de consumidor, redelivery, DLQ, orden/reintentos y versionado?


<!-- FIN 04_PLAN_DE_PRUEBAS_E2E.md -->

---

<!-- INICIO 05_PROMPTS_PARA_CODEX.md -->

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


<!-- FIN 05_PROMPTS_PARA_CODEX.md -->

---

<!-- INICIO 06_UUID_SALIENTES_ACTUAL_VS_OBJETIVO.md -->

# Decisión vigente de M5: normalizar SALIDAS propias a UUID primero; entregar contratos a consumidores después

**Decisión aclarada por el responsable de Rentas (25/10/2026).** Esta política prevalece sobre las advertencias anteriores de «esperar ratificación antes de tocar salidas». El código de M5 puede **preparar primero** su versión de salida y sus pruebas; **después** se comunica formalmente a los módulos consumidores para que adapten sus entradas. **No equivale a autorizar el envío real a Core antes de que los demás estén preparados.**

## 1. Tres reglas independientes, no intercambiables

1. **ENTRANTES a M5: NO cambiar el contrato que emite el módulo propietario.** `citizenUpdated` (M1), `ticketUpdated` (M2), `permitFeeGenerated`/`commercialFineGenerated` (M4), `infractionConfirmed` (M7), `socialBenefitUpdated` (M8) mantienen sus nombres, campos, tipos, IDs y semántica **tal como los publique cada dueño en su contrato efectivo**. Dentro de M5 sí se pueden corregir parsers, validadores, adaptadores, proyecciones y pruebas para aceptar esos mensajes, sin transformar lo que el otro módulo realmente manda ni atribuirle un cambio no acordado. Si un productor difiere del envelope Core o hay versiones contradictorias, registrar la incompatibilidad y consultarla con su dueño/Core; **no inventar un envelope o UUID entrante**.
2. **SALIENTES de M5: podemos modificarlos en nuestro código, y luego comunicar el contrato resultante a cada consumidor.** Estandarizar a UUID persistentes los *identificadores técnicos de entidades que pertenecen a M5*: `debtId`, `paymentId`, `requestId`, `exemptionId`, `planId`, `liquidationId` cuando exista en un payload, y `debtIds[]`. Mantener PK `Long` de BD. **Secuencia acordada: desarrollar M5 → validar JSON y regresiones locales → documentar contrato final/versionado → pasar JSON y esquemas a todos los consumidores → coordinar adaptación y prueba conjunta → recién después conectar/activar por Core.** No esperar aceptación externa para **preparar el código local**, pero tampoco afirmar que un consumidor ya soporta la nueva versión.
3. **ENVELOPE Core: INVARIABLE** para cualquier evento que **M5 publique** o reciba en el canal Core. Ocho campos exactos: `eventId`, `eventType`, `eventVersion`, `occurredAt`, `sourceModule`, `correlationId`, `causationId`, `data`. Para salientes `sourceModule="rentas"`, `eventId` UUIDv4 nuevo por hecho (mismo ID en reintento), `occurredAt` ISO 8601 con zona, `correlationId`/`causationId` UUID o null cuando el contrato lo permita y `data` payload. No agregar `producer`, `subject`, `payload`, `targetModule` a nivel raíz. **Cambiar el tipo de un ID dentro de `data` NO implica cambiar el envelope**; si requiere nueva `eventVersion`, definir y documentar con Core antes de activarlo, sin crear versiones o rutas fantasma.

## 2. Tabla práctica — estado conocido vs cambio a implementar en salidas

| Campo | Estado local reportado antes de traspaso | Acción decidida para productores M5 | Salvedad obligatoria |
|---|---|---|---|
| `eventId` | UUIDv4 outbox | Conservar patrón; mismo ID en reintentos | ID de evento ≠ ID entidad |
| `correlationId` / `causationId` | Core de 8 campos; M7 ya conserva correlación del origen | No inventar correlación desde PK `Long` | Conservar identidad causal verificable |
| `ticketId` (`updateTicketStatus`) | UUID de M2 conservado | **No cambiar** | Identidad propiedad M2 |
| `updatedBy.id` (M2) | UUID de empleado M5 V24 | **No cambiar** | UUID actor persistente |
| `requestId`, `exemptionId` | UUID M5 estables (V25) | Conservar como están en eventos salientes | `citizenId` verificado o `DEFERRED` |
| `debtId` (`overdueDebt`, económicos compartidos, solicitudes de planes) | UUID V25 disponible pero pagos M4/M7 todavía emitían `debt-<Long>` | Usar **mismo UUID persistido V25** en todas las salidas M5 en que represente la deuda | Compatibilidad externa queda pendiente de distribuir/validar |
| `paymentId` (`paymentRegistered`, `paymentReversed`) | `payment-<Long>`; no se informó UUID pago persistente en V25 | Diseñar/agregar UUID M5 estable por pago, sin tocar PK `Long` | Migración nueva: probar aislada y pedir autorización antes de `rentas` |
| `planId` (`updatePaymentPlanStatus`) | UUID persistente no confirmado | Verificar si existe; si no, crear UUID propio estable | No presumir que V25 lo incluyó |
| `debtIds[]` (`paymentPlanRequested`) | contrato M5 propuesto | UUID existentes V25 de cada deuda M5 | Respetar misma entidad en todos los eventos |
| `citizenId` | correspondencia M1 no confirmada; valores anteriores `Long` | Sólo UUID **canónico real de M1** cuando se conozca | Si falta, `DEFERRED`; no inventar por DNI/CUIT/Long |
| `originId` para `TRAFFIC_FINE` | `infractionId` original UUID M7 | **No cambiarlo** | Siempre mismo UUID externo recibido |
| `originId` para `PERMIT_FEE`/`COMMERCIAL_FINE` | `data.id` original M4, incluso si es `FEE-001` / `FINE-001` | **No cambiarlo**; no pertenece a M5 | Sólo M4 puede sustituir su identidad de origen y comunicar correspondencia |
| `originId` para `MUNICIPAL_TAX` | `debtSettled` diferido | Propuesta: UUID estable de liquidación propia de M5, verificar el modelo | Sin `citizenId` canónico no emitir un mensaje inválido |
| `conceptCode`, `originType`, `status`, DNI/CUIT, fechas, importes, `reason` | Datos de negocio | **No convertir a UUID** | Mantener campos/semántica pactados salvo ajuste explícito del productor M5 |

**Matiz importante M7 V4:** `paymentId` y `debtId` están tipados como **string** (no exige UUID). Un UUID en forma JSON string sigue siendo string, pero **compatibilidad de tipo no garantiza compatibilidad semántica** si M7 guarda o valida formatos anteriores. El nuevo formato se implementa en M5 primero, luego se entrega el contrato a M7; su aceptación y adaptación siguen pendientes. No confundirlo con prohibición de cambiar los salientes de M5.

## 3. Evento compartido = UNA especificación emitida por M5

- `paymentRegistered` → M4 y M7.
- `debtSettled` → M4, M7 y M8.
- `paymentReversed` → M4 y M7.
- `exemptionRequested` y `updateExemptionStatus` → M1 y M8.
- `paymentPlanRequested` / `updatePaymentPlanStatus` → M1.
- `overdueDebt` → M8; `updateTicketStatus` → M2.

**M5 puede actualizar el esquema saliente compartido sin esperar a que los otros equipos hagan el cambio**, pero debe hacerlo **una sola vez de forma consistente**: mismo `eventType`, mismas claves de `data` y tipos para todos los destinos; conservar `originType`, `originId`, `infractionId` y los datos económicos reales. No crear nombres diferentes por consumidor ni derivar dos identidades de deuda para el mismo hecho.

La distribución a consumidores es un hito posterior **obligatorio**. Si M4/M7/M8/M1 muestran incompatibilidades, se coordinan y corrigen *sus* receptores o se acuerda transición/versionado antes de **activar Core**. No se atribuye aceptación por anticipado.

## 4. Ejemplo ilustrativo: JSON final objetivo M5, NO evento ya emitido

Ejemplo de `paymentRegistered` de una multa de M7. UUID de pago y deuda son **propios y persistentes de M5**; `originId` y `infractionId` son **UUID ajeno idéntico, emitido por M7**. No se altera envelope:

```json
{
  "eventId": "4c712abe-b51f-4e09-9d32-b2ff31ce97b4",
  "eventType": "paymentRegistered",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-25T18:30:00-03:00",
  "sourceModule": "rentas",
  "correlationId": "ca16af37-771a-4912-8e6a-1919c341a113",
  "causationId": null,
  "data": {
    "paymentId": "78168ad3-372b-49bc-a08e-fbf69a2bfd5a",
    "debtId": "02c9affc-dcef-4d28-807b-fb530fd5f5f4",
    "originType": "TRAFFIC_FINE",
    "originId": "2a946899-a23a-40fb-af43-c70bfe66119a",
    "infractionId": "2a946899-a23a-40fb-af43-c70bfe66119a",
    "amountApplied": 50000,
    "paidAt": "2026-10-25T18:30:00-03:00",
    "remainingBalance": 25000
  }
}
```

**No copiar los UUID de este ejemplo a datos reales**. Todas las claves reales de entidad salen de registros persistidos y referencias oficiales. Si el tipo/semántica de `causationId` exige valor en un contrato actualizado, obtener el real; no simularlo.

## 5. Secuencia obligatoria para el nuevo Codex

1. Auditar el repositorio clonado y las últimas salidas actuales; comprobar si V25 ya da UUID en deuda, y si `payment` y `plan` necesitan UUID nuevos.
2. Continuar con **M4**: diagnosticar eventos *entrantes* y conservar su formato tal cual. Corregir consumidores/proyecciones internos de M5 si hace falta, sin modificar el payload enviado por M4.
3. **Preparar cambios salientes M5** a UUID estables: diseñar DTO, persistencia mínima y reglas, con pruebas por `originType` y por consumidor. Es un cambio que **sí puede desarrollarse localmente antes de ratificación externa**. No cambiar estructura/nombres del envelope Core.
4. Mantener `DEFERRED` para falta de UUID M1 o referencia externa legítima, y no reactivar `debtSettled` municipal basado en una identidad inventada. Documentar qué casos están listos y cuáles aguardan vínculos verificados.
5. Entregar **contratos M5 emitidos** a consumidores: JSON completo + JSON Schema/DTO si existe + tabla `campo/tipo/required/nullable` + casos de pago parcial, total, reversión, exención aprobada/rechazada + `eventVersion` + comportamiento de idempotencia y filtrado por `originType`. Consumidores: M1, M2, M4, M7, M8 según evento.
6. Registrar quién confirmó recepción/adaptación del contrato y sus diferencias, y preparar regresiones por consumidor; si hay cambios incompatibles activos, coordinar versión/transición **antes de habilitar Core**.
7. Con M1 confirmado, conciliar `citizenId`; después conectar Core/RabbitMQ y finalmente ejecutar pruebas E2E de portales. No etiquetar `LocalLog` como entrega real.

## 6. Instrucción resumida lista para Codex

> En este proyecto diferenciamos autoría de eventos: **NO modificamos los contratos ENTRANTES de M1/M2/M4/M7/M8**, sólo adaptamos nuestro consumer interno a lo que ellos publican. **SÍ modificamos nuestros eventos SALIENTES** para llevar los identificadores técnicos propiedad M5 a **UUID persistentes**, primero en el código y las pruebas de M5; luego entregamos los contratos actualizados a **todos** los consumidores para que adapten sus receptores. Mantenemos `originId` externo exactamente como llegó y el `citizenId` sólo cuando M1 proporcione su UUID canónico. **SIEMPRE usamos los ocho campos del envelope oficial Core, sin agregar/quitar/renombrar campos.** Las migraciones sobre `rentas`, el despliegue real por Core y el envío a consumidores requieren sus pasos de validación/autorización separados. Un solo payload por evento compartido, con regresión de M4/M7/M8/M1.


<!-- FIN 06_UUID_SALIENTES_ACTUAL_VS_OBJETIVO.md -->

---

<!-- INICIO 07_ENTRANTES_SALIENTES_CORE_Y_DISTRIBUCION.md -->

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


<!-- FIN 07_ENTRANTES_SALIENTES_CORE_Y_DISTRIBUCION.md -->

---

<!-- INICIO AGENTS.md -->

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


<!-- FIN AGENTS.md -->

---

<!-- INICIO README_PARA_GITHUB.md -->

# Cómo instalar esta entrega dentro del repositorio GitHub

## Contenido

- `00_LEER_PRIMERO.md`: traspaso ejecutivo, estado y mapa de archivos.
- `01_ESTADO_Y_ARQUITECTURA.md`: qué se implementó en M2, M8 y M7, decisiones y pendientes.
- `02_CONTRATOS_DE_EVENTOS.md`: catálogo de entradas/salidas M5, tipos, estados, comparativas, compatibilidad y ejemplos identificados como ilustrativos.
- `03_BACKLOG_PRIORIZADO.md`: próximo desarrollo M4, acuerdos M1/M7/M8, Core y checklist.
- `04_PLAN_DE_PRUEBAS_E2E.md`: pruebas futuras backend, broker y actualización real de portales.
- `AGENTS.md`: instrucciones persistentes de trabajo para Codex.
- `contratos_originales/`: documentos M1 v2.1, M2 v1.78 WIP, M4 octubre trabajo, M7 V4 19/10 y M8; también alcance funcional M5.
- `propuestas_no_ratificadas/`: propuestas de estandarización UUID/implementación, no prueba de aceptación por equipos externos.

- `06_UUID_SALIENTES_ACTUAL_VS_OBJETIVO.md`: **decisión vigente**: modificar primero eventos propios M5 a UUID estables, luego pasar contratos a consumidores; nunca alterar entradas ajenas ni el envelope de Core.
- `07_ENTRANTES_SALIENTES_CORE_Y_DISTRIBUCION.md`: manual operativo de autoría, entrega de contratos salientes y orden de validación/activación.

## Instalación recomendada

1. Extraer ZIP localmente, copiar **los `.md` excepto `AGENTS.md`** y directorios a `docs/handoff/` en el repo.
2. Copiar `AGENTS.md` a la raíz del repo sólo si no existe otro; si existe, integrar manualmente contenido sin perder reglas anteriores.
3. Revisar en grupo si el repo debe ser privado. Los contratos originales pueden contener información operativa/identificadores de prueba de los equipos; confirmar permiso antes de compartirlos públicamente.
4. Publicar **el código real y sus migraciones y pruebas** mediante git desde la computadora que contiene los cambios. Este ZIP **no contiene código fuente ni la base de datos**.
5. Pedir al desarrollador amigo que clone, lea `AGENTS.md` y la documentación, luego **primero audite** la rama (no hacer cambios masivos a ciegas).

## Seguridad Git

- `git status --short`; revisar `git diff --stat`, `git diff --check`, archivos no versionados y `git diff --cached` antes del commit.
- No incluir credenciales (`.env`, secrets, JWT, API keys), archivos de respaldo `.dump`, datos personales, bases, dependencias pesadas, `target`, `node_modules`, temporales, logs o caches de Codex.
- Ejecutar suites relevantes; subir cambios por branch/PR revisable.

## Limitación temporal

Los informes aquí reflejan el estado transmitido el **25/10/2026**; no implican acceso al contenido real del repositorio publicado después de esa fecha. Cualquier respuesta nueva de M1/M4/M7/M8/Core debe adjuntarse y registrarse con fecha, versión, responsable y cambios de contrato.


<!-- FIN README_PARA_GITHUB.md -->
