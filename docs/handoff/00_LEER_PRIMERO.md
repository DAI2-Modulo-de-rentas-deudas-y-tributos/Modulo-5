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
