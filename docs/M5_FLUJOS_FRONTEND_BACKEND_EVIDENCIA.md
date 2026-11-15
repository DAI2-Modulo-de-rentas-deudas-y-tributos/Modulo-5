# M5 — evidencia de flujos frontend/backend

## Alcance y niveles

- **N1, M5 local real:** frontend React, Spring Boot y PostgreSQL 17.11 aislada/efímera. Playwright recorrió el portal con un pago parcial de $40 sobre una deuda sintética de $100. El reintento con la misma `Idempotency-Key` devolvió HTTP 201 y los mismos IDs de intento y pago; la deuda quedó con saldo $60. Luego se consultó ese saldo autenticándose como TAXPAYER, RENTAS y CASHIER; la pantalla de Caja mostró $60. La consola no registró errores JavaScript; sí hubo dos respuestas 404 del favicon. No se conectó Core/RabbitMQ ni otros módulos.
- **Pruebas de interfaz:** Vitest ejecuta componentes/servicios contra `fetch` simulado; prueba rutas, estados y requests esperados, pero no acredita persistencia real ni visibilidad real entre sesiones.
- **N2, contractual:** pruebas de consumidores/productores y Testcontainers con fixtures. Es evidencia del contrato local, no de interoperabilidad externa.
- **N3, intermodular real:** no disponible; requiere backends/portales externos, Core, routing y ACK comprobable.

## Matriz de flujos

| Flujo | UI y API esperada | Regla/persistencia | Evidencia ejecutada | Refresco/otra sesión/roles | Estado |
|---|---|---|---|---|---|
| Configuración tributaria | React propone/consulta; POST configuración, submit y approve | Versionado y autorización RENTAS/SUPERVISOR | Vitest `configuracionTributos`; suites de configuración/seguridad en `clean verify` | UI simula HTTP; autorización backend tiene MockMvc; no se automatizó una sesión real en navegador | Parcial verificado |
| Liquidación | Preview y POST `/liquidations` | Strategy, topes/reducciones, Liquidation y Debt | `TaxCalculationStrategiesTests`, `PercentageBenefitRegressionTests`; `PostgreSqlIntegrationTest.percentageStrategyPreviewAndPersistedLiquidationMatchOnPostgreSql` | PostgreSQL confirma preview = liquidación = saldo inicial; flujo visual de liquidación no cubierto por esta ejecución | PASS backend/PG; UI simulada |
| Generación de deuda | Crear liquidación genera deuda en transacción | Debt enlazada a Liquidation | `DomainFlowTests.liquidationWithActiveConfigurationCreatesDebtAtomically`; PostgreSQL Strategy test | Persistencia PG verificada en el caso porcentual; otras sesiones cubiertas por autorización/consultas, no por UI real | PASS backend |
| Emisión de boleta | UI solicita boleta | Snapshot, relación deuda-boleta e idempotencia | `BillingFlowTests`, tests de boleta/idempotencia PostgreSQL y Vitest de boletas | Refresco UI es simulado; test de propiedad API | PASS backend; UI simulada |
| Pago electrónico | Portal solicita preview y registra pago con `Idempotency-Key` | Payment/Allocation actualizan saldo | Vitest `lote2Front`; E2E Playwright real: $40 sobre deuda $100; mismo intento/pago al reintentar la clave | Browser obtuvo el saldo $60 autenticándose en TAXPAYER, RENTAS y CASHIER; PostgreSQL efímera | PASS visual + API + PG |
| Pago parcial/saldo | Portal/Caja muestra saldo actualizado | Imputación parcial y balance económico | E2E Playwright real y tests concurrentes PostgreSQL | Misma deuda reflejó $60 en las tres sesiones/roles; Caja mostró saldo y vencido por separado | PASS visual + backend/PG |
| Pago total | Portal/caja registra cancelación total | Debt pasa a PAID, imputación completa | `DomainFlowTests.fullPaymentSettlesDebtAndCreatesOutboxAllocation`; tests PG de pagos | Pruebas de servicio/API, no sesión gráfica real | PASS backend |
| Saldo a favor | Aplicar crédito sobre deuda elegible | CreditBalanceApplication trazable; sin saldo negativo | `PostgreSqlIntegrationTest` y suites de crédito/reversión | Persistencia y ownership se cubren en backend; no se probó cross-session UI real | PASS backend/PG según casos |
| Solicitud/aprobación de plan | Portal solicita; Rentas/Supervisor resuelve | Estados y propiedad/rol | `PaymentPlanFlowTests`, `SecurityTests`; Vitest portal/dashboard | La suite UI usa API simulada; persistencia PostgreSQL cubierta por tests de integración del plan donde se indica | Backend verificado; UI simulada |
| Pago de cuota | Caja envía `installmentId`; contribuyente consulta el plan | Allocation reduce installment y plan | Tests de allocations/cuotas y `caja.test.jsx` | UI test simulado; no se realizó sesión real de caja + portal en navegador | UI E2E pendiente |
| Reversión autorizada | Solicitud, aprobación/rechazo y ejecución por roles | Estado, motivo, pago/deuda/allocation y auditoría | `ReversalEligibilityTests`, `ReversalRejectionTests`, `SecurityTests`; Vitest de caja | Revalidación y autorización se prueban backend; frontend usa fetch simulado | PASS backend; UI simulada |
| Exención | Contribuyente presenta; Rentas evalúa; Supervisor resuelve | Solicitud/documentos/Exemption y auditoría | `ExemptionWorkflowTests`, `PercentageBenefitRegressionTests`, tests de privacidad | El backend verifica titular/rol; carga de archivo externo no está conectada y React lo informa | Parcial: workflow sí, archivo y UI real no |
| Beneficio/descuento | Portal consulta beneficio; liquidación aplica reducción | Consumo M8 y snapshot componente | `PercentageBenefitRegressionTests`, `M8SocialBenefitFlowTests`, suites PostgreSQL de contrato | Fixtures cubren contrato; M8/Core real bloqueados | PASS local/contractual |
| Historial y auditoría | Pantallas consultan deuda/pagos/plan/exención | Eventos funcionales y audit log | `DebtHistoryTests`, `HistoricalMovementAuditPostgreSqlTest`, API ownership tests | Persistencia se valida por PostgreSQL en pruebas dedicadas; refresco React simulado | PASS backend/PG para escenarios cubiertos |

## Límites de la evidencia

La suite ejecutada: backend `clean verify` = 434 tests, 0 fallos/errores/omitidos; Testcontainers PostgreSQL 17.11 con Flyway V28; frontend Vitest = 319 tests en 35 archivos; build Vite exitoso. JaCoCo: líneas 91,61%, instrucciones 90,32%, ramas 65,51%, gate aprobado. El build mantiene el aviso de chunk JS mayor a 500 kB. La evidencia Playwright cubre pago parcial y lectura de saldo entre TAXPAYER, RENTAS y CASHIER, no los demás recorridos gráficos de esta matriz.

Para repetir el E2E, iniciar PostgreSQL 17 efímera, backend Spring Boot perfil `dev` y Vite apuntando al backend; crear identidad DEMO y deuda sintética, pagar parcialmente desde el portal, repetir la misma clave y consultar el saldo en sesiones de RENTAS y CASHIER. No apuntar a `rentas` compartida. El reinicio físico del backend se valida por separado en `PhysicalRestartEconomicEventTest`.
