# Reporte final de integración frontend/backend M5

## Estado final

`FRONTEND_BACKEND_FULL_STACK_VALIDATED`

La integración técnica se validó de extremo a extremo con navegador real, frontend Vite en modo API, backend Spring Boot y PostgreSQL 17 real. Se recorrieron los cinco roles y se corrigieron incompatibilidades objetivas de presentación/adaptación encontradas durante el recorrido.

La aprobación estricta `LIVE_PC_VALIDATED` no se declara: la aplicación no ofrece controles visuales de paginación y los flujos económicos A-F no quedaron todos repetidos desde controles de UI en esta ejecución. La evidencia E2E real existente continúa verde en API/PostgreSQL, pero no sustituye esos dos requisitos visuales explícitos.

`LIVE_PC_VALIDATION_NOT_APPROVED`

Frontend URL exacta informada por Vite: `http://127.0.0.1:5173/`

## Full stack

| Componente | Resultado |
|---|---|
| Docker | Docker Desktop 29.7.2 operativo |
| PostgreSQL local | `postgres:17-alpine`, PostgreSQL 17.11, healthy, Compose aislado `m5integrationfinal` |
| Backend | Spring Boot conectado a PostgreSQL; Actuator `200 UP`; `/api/v1/health` 200 |
| Frontend | Vite en `http://127.0.0.1:5173/`, proxy `/api` hacia backend real |
| Datos de dominio | `VITE_USE_MOCKS=false`; `DOMAIN_MOCKDB_USED=false` durante E2E |

Flyway aplicó V1-V12 sobre una base nueva y Hibernate validó el modelo con `ddl-auto=validate`.

## Live PC test

| Evidencia | Resultado |
|---|---|
| Browser | Codex In-app Browser real para PERSONAL; smoke manual informado para los otros cuatro roles |
| Frontend | `http://127.0.0.1:5173/`, Vite real |
| Backend | `http://127.0.0.1:8080`, Spring Boot real, contenedor `healthy` |
| Database | `postgres:17-alpine`, PostgreSQL 17.11 real, contenedor `healthy` |
| Domain mocks used | `false` (`VITE_USE_MOCKS=false`) |
| Network | UI → `apiClient` → adapters → proxy `/api` → backend → PostgreSQL; endpoints de taxpayers/debts respondieron 200 por el proxy |
| Console | Smoke final desde pestaña nueva: 0 warnings y 0 errors |
| Refresh/persistence | Contribuyentes reales continuaron visibles tras recarga directa |
| Routing | Rutas internas `/rentas`, `/caja`, `/auditor` y `/portal` cargaron sin 404 durante el recorrido |
| Role switch | PERSONAL, SUPERVISOR, CAJERO, AUDITOR y CONTRIBUYENTE cambiaron navegación/permisos |
| Pagination | Metadata Spring Page preservada; no existe paginador visual para cambiar de página |
| Filters | Filtro visual de contribuyentes validado con datos reales |
| Enums | Se eliminaron enums crudos observados, incluido `PARTIALLY_PAID` |
| Error UI | Estado inválido de exención mostró mensaje comprensible y no produjo crash |

Resultado por rol:

- PERSONAL: browser smoke automatizado; dashboard, contribuyentes, detalle, tributos, liquidaciones, deudas, boletas, pagos, ajustes, planes, refinanciación, exenciones y tickets recorridos. Smoke limpio final verde.
- SUPERVISOR: `MANUAL VISUAL SMOKE: PASSED`.
- CAJERO: `MANUAL VISUAL SMOKE: PASSED`.
- AUDITOR: `MANUAL VISUAL SMOKE: PASSED`.
- CONTRIBUYENTE: `MANUAL VISUAL SMOKE: PASSED`.

Defectos corregidos durante el recorrido:

- crashes por shapes incompletos en Planes, Caja, Auditor e indicadores;
- adaptación incorrecta de recursos anidados del contribuyente;
- fechas civiles desplazadas un día;
- enums crudos, importes/campos ausentes y estados de imputación engañosos;
- resumen del portal contradictorio y falta de obligaciones reales;
- regresiones cubiertas por tests nuevos de adapters y formato.

Estado E2E visual:

- A pago total: resultado persistido visible y validado por API/SQL; no repetido íntegramente desde UI en esta ejecución.
- B pago parcial: resultado real visible (`Pago parcial`, saldo 60); no repetido íntegramente desde UI.
- C sobrepago: resultado persistido validado por API/SQL; no repetido íntegramente desde UI.
- D plan: plan/cuotas persistidos y pantalla corregida; no creado nuevamente desde UI.
- E reversión: integridad principal/interés validada por API/SQL/Testcontainers; no repetida desde UI.
- F configuración: transición persistida validada por API/SQL; no repetida desde UI.

Arranque limpio final:

- PostgreSQL detenido/iniciado sin borrar volúmenes y recuperado `healthy`.
- Backend reconstruido desde Dockerfile y recreado con `RENTAS_SECURITY_DEV_MODE=true` sólo para integración local.
- Flyway validó 12 migraciones y confirmó schema en V12; Hibernate inicializó correctamente.
- Actuator `UP`, health API 200, OpenAPI 122 paths/136 operaciones y cero `/api/v1/events*`.
- Vite reiniciado en el puerto informado `5173` con datos de dominio reales.
- Smoke PERSONAL final: login → dashboard → contribuyentes → refresh → deudas, sin errores de consola.

## E2E real sobre PostgreSQL

- A: liquidación, deuda, boleta y pago total; deuda `PAID`, saldo 0.
- B: pago parcial de 40 sobre deuda 100; deuda `PARTIALLY_PAID`, saldo 60.
- C: sobrepago 120 sobre deuda 100; allocated 100, unallocated 20 y crédito 20.
- D: solicitud concedida, plan y dos cuotas creadas.
- E: cuota con principal 50 e interés 6; reversión restituyó sólo principal y dejó pago/allocation `REVERSED`.
- F: configuración tributaria `DRAFT → submit → approve`, estado `ACTIVE`, versión 1.

## Seguridad y roles HTTP

- PERSONAL, SUPERVISOR, CAJERO, AUDITOR y CONTRIBUYENTE: lecturas y flujos representativos 200.
- Ownership ajeno: 403 `FORBIDDEN_OWNERSHIP`.
- Escritura de AUDITOR: 403 `ACCESS_DENIED`.
- Contrato de error: 400, 403, 404 y 409 con `code`, `message` y `traceId`.
- `RENTAS_SECURITY_DEV_MODE` y `VITE_DEV_IDENTITY_HEADERS` tienen default `false`.
- En modo Core el frontend envía cero headers `X-Dev-*`; Core/JWT productivo continúa bloqueado externamente.

## Paginación y enums

Se validaron respuestas Spring Page reales para taxpayers, debts, liquidations y payments. `content`, `number`, `size`, `totalElements`, `totalPages` y `last` se preservan en `array.page`, sin truncamiento silencioso y con `size <= 100`.

La capa adapter traduce en ambos sentidos los enums reales de CalculationType, TaxConceptType, DebtOriginType, DebtStatus, PaymentStatus, PaymentMethod, PaymentOrigin, PaymentPlan, Installment y Exemption. Se corrigieron, entre otros, `FIXED→FIJO`, `FEE→TASA`, `LIQUIDATION→SETTLEMENT`, métodos/orígenes de pago y estados de planes, cuotas y exenciones. Los writes inversos comprobados incluyen `PORCENTAJE→PERCENTAGE` y `TARJETA_CREDITO→CARD`.

El dashboard usa cero numérico cuando el backend no informa planes o exenciones; no introduce datos ficticios y no devuelve `null`, `undefined` ni `NaN`. El caso tiene prueba de regresión verde.

## Diez capacidades MISSING_BACKEND

| # | Operación | Pantalla | Capacidad ausente o equivalente | Clasificación |
|---:|---|---|---|---|
| 1 | `settlement.issue` | Liquidaciones | Segundo comando de emisión; crear liquidación ya emite y genera deuda | `NOT_REQUIRED` |
| 2 | `debt.reportOverdue` | Deudas | Reportar deuda vencida hacia M8 | `EXTERNAL_BLOCKED` |
| 3 | `adjustment.execute` | Ajustes | Ejecución separada; approve ya aplica el ajuste | `NOT_REQUIRED` |
| 4 | `credit.applicableDebts` | Saldos a favor | Lista dedicada de deudas aplicables; se compone con deudas del contribuyente y el backend valida al aplicar | `NON_CRITICAL_UI` |
| 5 | `refinancing.eligiblePlans` | Refinanciación | Lista dedicada de planes elegibles; el backend valida al solicitar | `NON_CRITICAL_UI` |
| 6 | `cashier.taxpayerFile` | Caja | Legajo 360° completo; summary existente es parcial | `NON_CRITICAL_UI` |
| 7 | `cashier.agents` | Caja | Catálogo de agentes, propiedad de Core | `EXTERNAL_BLOCKED` |
| 8 | `cashier.dailySummary` | Caja | Agregado diario dedicado; pagos por fecha permiten composición parcial | `NON_CRITICAL_UI` |
| 9 | `audit.indicators/breakdown` | Auditoría | Desglose genérico por filas; existen summary e indicadores específicos | `NON_CRITICAL_UI` |
| 10 | `portal.notices` | Portal contribuyente | Recurso de avisos dedicado; hoy se derivan desde deudas propias | `NON_CRITICAL_UI` |

Ninguno es `CRITICAL_UI_BLOCKER` para las historias principales E2E A-F. Deben abrirse tareas posteriores para los seis `NON_CRITICAL_UI`; los dos `EXTERNAL_BLOCKED` dependen de M8/Core; los dos `NOT_REQUIRED` requieren alinear la semántica visual y no crear endpoints duplicados.

## Tests finales

### Frontend

- 20 archivos de test.
- 269 tests detectados y aprobados; 0 fallidos.
- `npm run build`: exitoso; 1778 módulos transformados.
- Bundle principal: 1.082,94 kB minificado, 287,17 kB gzip. Warning >500 kB no bloqueante.

### Backend

- `mvnw.cmd clean verify`: `BUILD SUCCESS`.
- 97 tests; 97 aprobados; 0 failures; 0 errors; 0 skipped.
- Testcontainers 1.21.3 conectó con Docker Desktop 29.7.2.
- `PostgreSqlIntegrationTest`: 11/11 aprobados sobre `postgres:17-alpine`, PostgreSQL 17.11.
- Flyway V1-V12 aplicadas; no fue necesaria V13.
- Hibernate validate exitoso.
- JaCoCo: líneas 88,65%, instrucciones 86,80%, branches 56,40%; gate de líneas >=85% aprobado.

Los 11 casos PostgreSQL ejecutados fueron:

1. `confirmedContractProjectionAndExternalEventIdConstraintsExist`
2. `concurrentOutboxWorkersLockEachEventOnce`
3. `installmentPaymentAndReversalPreservePrincipalInterestBreakdown`
4. `optimizedQueriesRemainBoundedOnPostgreSql`
5. `contextStartsAndFlywayAppliesEveryMigration`
6. `paymentsOverpaymentsCreditAndLocksRunOnPostgreSql`
7. `preliminaryM4ContractsRunOnPostgreSqlWithoutEconomicEffects`
8. `planReversalAndCreditSingleExecutionLocksRunOnPostgreSql`
9. `economicChecksAndIdempotencyUniquesExist`
10. `recommendedOperationalIndexesExist`
11. `m7ContractIdempotencyResolutionRollbackAndConcurrencyRunOnPostgreSql`

## OpenAPI e integraciones externas

- OpenAPI: 122 paths, 136 operaciones, cero endpoints `/api/v1/events*`.
- M4: contrato preliminar validado en PostgreSQL sin efectos económicos; se preserva `BLOCKED_M4_TAXPAYER_RESOLUTION` hasta obtener `establishmentId → taxpayer`.
- M7 inbound: contrato, resolución DNI/CUIT, importe final, idempotencia, rollback y concurrencia validados en PostgreSQL.
- Core/JWT: `EXTERNAL_BLOCKED`.
- Broker y outbound M4/M7/M8: `EXTERNAL_BLOCKED`.
- CORS de deployment: `CORS_DEPLOYMENT_PENDING` hasta conocer la URL final de Amplify; local usa proxy Vite.

## Pendientes para aprobar LIVE_PC_VALIDATED

- Implementar o acordar el alcance del paginador visual; hoy no hay control de cambio de página en las tablas requeridas.
- Repetir A-F desde la UI cuando las pantallas expongan las acciones y los contratos externos desbloqueados permitan ejecutarlas sin falsear el dominio.
- Inspección detallada de request/response payload en una herramienta Network: el navegador controlado expuso consola pero no un inspector de red; la ruta real se corroboró mediante proxy, respuestas HTTP, UI y PostgreSQL.

## Variables de entorno / secretos

- Agregadas: `VITE_AUTH_MODE`, `VITE_DEV_IDENTITY_HEADERS`, `RENTAS_SECURITY_DEV_MODE`.
- Defaults: seguros (`false` para los dos flags de identidad/desarrollo).
- Secretos nuevos: ninguno.
- `.env` real: ninguno.
- Credencial local de PostgreSQL: el rol del volumen se realineó en runtime con la configuración local ya declarada por Compose; el valor no se mostró ni se agregó a documentación.
- CI/CD: no habilitar identidad dev en producción; configurar sólo los secretos ya requeridos por el entorno.
- `.env.example`: actualizado con placeholders/defaults seguros.

No se agregaron, eliminaron ni renombraron variables de entorno. No se crearon secretos nuevos. La única modificación de secreto fue la realineación local indicada arriba; no requiere cambio de CI/CD ni de `.env.example`.

## Acciones Git/entrega

- Commit: no realizado.
- Push: no realizado.
- Merge: no realizado.
- Deploy: no realizado.
