# Validación final con PostgreSQL real — Módulo 5 Rentas

Fecha: 2026-08-31. Rama: `feature/backend-rentas-hardening`.

Esta validación se ejecutó sin commit, push, merge ni deploy. No se modificaron frontend, infraestructura, Core/JWT, broker ni contratos externos. Los contenedores y el volumen creados para la prueba fueron temporales y se eliminaron al finalizar.

## Docker

- CLI: Docker 29.7.2, build `a7dcaa6`.
- Compose: v5.4.0.
- Daemon: Docker Desktop Linux, versión 29.7.2, operativo.
- Smoke test: `docker run --rm hello-world` finalizó correctamente.

## PostgreSQL y Testcontainers

- Imagen: `postgres:17-alpine`.
- Versión observada: PostgreSQL 17.11.
- Contenedor Testcontainers: iniciado correctamente y eliminado automáticamente al concluir.
- JDBC: URL temporal PostgreSQL proporcionada por Testcontainers; no se registran credenciales.
- Suite PostgreSQL: 11 tests ejecutados, 11 aprobados, 0 fallidos, 0 errores, 0 omitidos.

Tests ejecutados:

1. `contextStartsAndFlywayAppliesEveryMigration`
2. `economicChecksAndIdempotencyUniquesExist`
3. `recommendedOperationalIndexesExist`
4. `confirmedContractProjectionAndExternalEventIdConstraintsExist`
5. `installmentPaymentAndReversalPreservePrincipalInterestBreakdown`
6. `concurrentOutboxWorkersLockEachEventOnce`
7. `preliminaryM4ContractsRunOnPostgreSqlWithoutEconomicEffects`
8. `m7ContractIdempotencyResolutionRollbackAndConcurrencyRunOnPostgreSql`
9. `paymentsOverpaymentsCreditAndLocksRunOnPostgreSql`
10. `planReversalAndCreditSingleExecutionLocksRunOnPostgreSql`
11. `optimizedQueriesRemainBoundedOnPostgreSql`

## Flyway e Hibernate

- Flyway aplicó V1–V12, en orden, sobre una base PostgreSQL vacía.
- `flyway_schema_history` registró las doce migraciones con `success=true`.
- No fue necesaria una V13 y no se editaron V1–V12.
- Hibernate inició con `ddl-auto=validate` y validó correctamente el modelo JPA contra PostgreSQL 17.11.

## Integridad económica y concurrencia

- Pago total, parcial y sobrepago validados.
- Sobrepago validado con `120 = 100 allocated + 20 unallocated` y saldo a favor de 20.
- `PaymentAllocation` validó `principalApplied + interestApplied = amount`.
- Cuota 80/20 validada; la reversión restituye sólo los 80 de capital.
- Se validaron los checks de `CreditBalance`, XOR de origen de `Debt` y XOR de destino de `PaymentAllocation`.
- Se probaron pagos simultáneos sobre una deuda, uso concurrente de crédito, creación concurrente de planes, reversión duplicada, M7 concurrente y workers Outbox concurrentes, sin doble efecto económico ni saldo negativo.

## Integraciones

### M7

- `sourceModule=transito` aceptado y normalización interna `M7` validada.
- Resolución DNI/CUIT, `finalAmount`, idempotencia y concurrencia validadas con PostgreSQL.
- Payload inválido y contribuyente inexistente hacen rollback sin efecto económico parcial.

### M4 preliminar

- Los contratos `permitFeeGenerated` y `commercialFineGenerated` ejecutaron sobre PostgreSQL.
- La idempotencia de la clave de negocio fue validada.
- Se preservó `BLOCKED_M4_TAXPAYER_RESOLUTION`: no se crean `ExternalObligation` ni `Debt` hasta recibir un contrato válido `establishmentId -> taxpayer`.

## Performance

- Liquidations: 1 consulta adicional por página en el escenario validado.
- Debts: 2 consultas por página.
- Bills: 1 consulta adicional por página.
- Indicators: 3 agregaciones SQL.
- Defaulted payment plans: 1–2 sentencias según paginación.
- No se detectó una regresión N+1 con PostgreSQL.
- V12 confirmó `idx_payment_status_paid_at` para `payment(status, paid_at)` e `idx_payment_plan_status` para `payment_plan(status)`.

## Spring Boot con Compose

- Se construyó el Dockerfile y se inició un proyecto Compose aislado con PostgreSQL 17.11.
- Backend y PostgreSQL alcanzaron estado healthy.
- `GET /actuator/health`: HTTP 200, `UP`.
- `GET /api/v1/health`: HTTP 200, base PostgreSQL.
- `GET /v3/api-docs`: HTTP 200, 122 paths y 136 operaciones.
- Endpoints `/api/v1/events*`: 0.

## Maven y cobertura

- Comando final: `mvnw.cmd clean verify`.
- Resultado: `BUILD SUCCESS`.
- Tests: 97 detectados, 97 aprobados, 0 failures, 0 errors, 0 skipped.
- Líneas: 814/922, 88,29%.
- Instrucciones: 17.637/20.298, 86,89%.
- Branches: 502/890, 56,40%.
- Gate JaCoCo de líneas >=85%: cumplido.

## Bugs exclusivos de PostgreSQL

No se encontraron bugs exclusivos de PostgreSQL. No fue necesario modificar producción ni el esquema.

## Cambios requeridos

Se ampliaron únicamente los escenarios de integración PostgreSQL en `PostgreSqlIntegrationTest` para cubrir M4, M7, economía, concurrencia, Outbox y consultas optimizadas. No se agregó funcionalidad de aplicación.

## Bloqueos externos preservados

- M4: resolución contractual `establishmentId -> taxpayer`.
- M7 outbound.
- Core/JWT.
- Broker, topics, ACK/DLQ y schemas.

## Variables de entorno / secretos

Sin cambios en variables de entorno ni secretos. Para Compose se usó una credencial ficticia y temporal limitada al proceso de validación; no se guardó en archivos ni documentación.

Estado: `POSTGRESQL_REAL_VALIDATED`.
