# Reporte de hardening y calidad — Módulo 5 Rentas

> Este archivo conserva el cierre histórico de hardening V9. La etapa posterior de contratos confirmados agrega V10 y 75 tests aprobados; el detalle vigente está en `INTEGRATION_CONTRACTS.md`.

Fecha de validación local: 2026-08-26. Rama local: `feature/backend-rentas-hardening`. Este reporte describe sólo el backend; no se modificaron frontend ni infraestructura.

## 1. Estado inicial

- `clean verify`: BUILD SUCCESS con JDK 21 compilando a Java 17; el `JAVA_HOME` configurado originalmente apuntaba a un JDK inexistente y se usó temporalmente el JBR disponible.
- Tests: 45 detectados, 42 aprobados, 0 failures, 0 errors y 3 Testcontainers omitidos.
- Cobertura global: 64,44% líneas; 66,91% instrucciones; 48,86% branches.
- Package `ar.gob.municipalidad.rentas`: 64,44% líneas.
- Flyway: 8 migraciones.
- Tiempo Maven: 43,146 s (46,274 s de pared).
- Warnings: operaciones unchecked en `FilteredQueryService`, API deprecated en `SecurityTests`, auto-attach de Mockito y Docker no disponible.

## 2. Cambios realizados

- Quality gate JaCoCo de línea global >= 85% en `verify`, sin exclusiones.
- Nueva migración V9 con constraints económicos, índices y desglose de imputación.
- `PaymentAllocation` conserva `principalApplied` e `interestApplied`.
- Se reforzaron locks en pagos, reversión, planes, caducidad, refinanciación, exenciones, corridas y Outbox.
- Errores HTTP uniformes para JSON/enum inválido, Bean Validation, integridad, concurrencia, autorización y fallos inesperados.
- Identificador de correlación propagado a respuesta, MDC, error y auditoría.
- El modo de identidad por headers quedó limitado a perfiles que lo habilitan explícitamente; ya no existe perfil `dev` por defecto.
- El Outbox limita el error persistido a la capacidad de la columna y bloquea el lote publicable.
- Se ampliaron contratos MockMvc, pruebas económicas, migraciones y PostgreSQL condicional.

## 3. Bugs reales encontrados y corregidos

1. Un sobrepago debe distinguir la imputación real del remanente convertido a saldo a favor. Ahora conserva `amount = allocatedAmount + unallocatedAmount` (por ejemplo, `120 = 100 + 20`) y bloquea una imputación directa posterior para evitar gastar dos veces el crédito.
2. Una imputación de cuota no guardaba cuánto era capital y cuánto interés; una reversión no podía reconstruir inequívocamente el principal. V9 y el modelo guardan ambos importes, cuya suma debe igualar la imputación.
3. El modo de autenticación por headers se activaba por perfil `dev` predeterminado. Se eliminó ese default para que el despliegue falle cerrado.
4. `AccessDeniedException` era capturada por el handler genérico y respondía 500. Ahora responde 403 seguro.
5. JSON y enums inválidos podían producir respuestas inconsistentes. Ahora responden 400 `INVALID_REQUEST`.
6. Errores de integridad y concurrencia no tenían traducción estable. Ahora responden 409 sin exponer SQL ni locks internos.
7. El importe mínimo `0.01` de ajustes se rechazaba por un `DecimalMin` exclusivo. Se corrigió y se probó vía HTTP.
8. Los errores largos del publisher podían superar la columna del Outbox. Ahora se truncan a 255 caracteres.
9. Auditoría no recibía correlación de request. El nuevo filtro la incorpora y evita fugas entre threads mediante `finally`.

## 4. Tests agregados o reforzados

- `WebApiReadContractTests`: páginas, recursos inexistentes, comandos sin aggregate, validación, flujo REST real de conceptos/configuración/liquidación/ajuste/boleta/pago/reversión/corrida/plan, DTOs, PDF, indicadores, auditoría y Outbox.
- `BoundaryHardeningTests`: mapeo del contrato público y traducción segura de fallos de infraestructura.
- `DomainFlowTests`: invariante contable de pagos, sobrepago y desglose de allocation.
- `PaymentPlanFlowTests`: capital/interés de cuota y reversión exacta.
- `MigrationValidationTest`: versión y cantidad de migraciones sobre H2 PostgreSQL mode.
- `PostgreSqlIntegrationTest`: V1-V9, checks, índices y regresión real de cuota 80/20 con reversión sobre PostgreSQL 17.

## 5. Resultado final

- `clean verify`: BUILD SUCCESS.
- Tests: 53 detectados; 53 aprobados; 0 failures; 0 errors; 0 skipped.
- Cobertura: 87,83% líneas (671/764), 87,37% instrucciones y 55,00% branches.
- Package único `ar.gob.municipalidad.rentas`: 87,83% líneas.
- Clases relevantes: `PaymentService` 100%, `LiquidationService` 100%, `ExternalObligationService` 100%, `GlobalExceptionHandler` 100%, `CorrelationIdFilter` 100%, `LiquidationRunService` 90%, `ReversalService` 85,71%, `PlanWorkflowService` 77,11% y `ApiController` 65,69%.
- JaCoCo: `All coverage checks have been met`.
- Tiempo final Maven con Testcontainers: 2 min 39 s.

## 6. PostgreSQL, Testcontainers y Flyway

- H2 PostgreSQL mode: validado en la suite.
- Flyway: V1 a V9 aplicadas en orden sobre base vacía; Hibernate `validate` inicia correctamente.
- PostgreSQL 17.11 real: validado con `postgres:17-alpine` tanto por Compose como por Testcontainers.
- Testcontainers: 4 tests ejecutados, 4 aprobados y 0 omitidos (los 3 originales más una regresión económica PostgreSQL).
- Flyway V1-V9 se aplicó desde un esquema vacío; `flyway_schema_history` confirmó las nueve migraciones exitosas.
- Hibernate `ddl-auto=validate` inició sin mismatch contra PostgreSQL 17.
- Dockerfile construido y Compose completo validado con backend y PostgreSQL healthy.

## 7. Integridad de datos y precisión

V9 agrega checks para importes, porcentajes, rangos, consistencia de liquidaciones, pagos, allocations, créditos, ajustes, planes, cuotas, exenciones, corridas e integración. También agrega índices para consultas frecuentes de deuda, liquidación, pagos, planes, cuotas, exenciones e integración. No se encontraron `double` ni `float` para dinero; los cálculos usan `BigDecimal`, escala 2 y rounding explícito.

## 8. Seguridad, concurrencia y transacciones

- No existe `permitAll` global; sólo health y documentación ya definida son públicas.
- Roles y ownership existentes continúan probados.
- La identidad falsa sólo funciona cuando `rentas.security.dev-mode=true` mediante perfil local/test explícito.
- No se implementó JWT/Core ni se inventaron claims.
- Locks pesimistas se aplican a aggregates económicos concretos, no a tablas completas.
- Los casos de uso críticos conservan límites `@Transactional`; los tests existentes verifican concurrencia de pagos, créditos, planes y reversión.

## 9. Outbox, idempotencia y auditoría

- El Outbox continúa creado dentro de las transacciones de dominio, con `PENDING`, retry, `FAILED`, `PUBLISHED` y `DEAD_LETTER`.
- La selección publicable ahora se bloquea para evitar dos workers sobre el mismo lote.
- Inbound conserva unique por `eventId` y business keys; no se conectó broker real.
- AuditEntry registra usuario, rol, timestamp, estado nuevo y correlationId. El overload para before/after quedó preparado; completar snapshots previos en todas las operaciones es deuda técnica.

## 10. OpenAPI y endpoints

- OpenAPI generado: 122 paths y 136 operaciones.
- API propia M5: 134 endpoints funcionales según la matriz existente.
- Se confirmó que no existen `/api/v1/events` ni `/api/v1/events/**`.
- No se eliminaron endpoints ni se cambió su path.
- Pendiente: mejorar tags, ejemplos, respuestas por operación y declarar el esquema definitivo de seguridad cuando exista el contrato Core/JWT.

## 11. Código muerto y naming

No se eliminaron componentes automáticamente. `DevDataInitializer` se clasifica KEEP para perfil dev; handlers/adapters de integración se clasifican PENDING_EXTERNAL_INTEGRATION; DTOs y repositorios alcanzados por Spring o OpenAPI se conservan. No se encontraron TODO/FIXME ni `System.out` en producción. El naming mixto español/inglés se mantuvo para no romper compatibilidad.

## 12. Deuda técnica y riesgos conocidos

- Cobertura branch (54,38%) es menor que line coverage; conviene ampliar transiciones negativas de `PlanWorkflowService`, `ExemptionService` y controllers.
- Testcontainers 1.21.3 necesita `-Dapi.version=1.44` con Docker Engine 29 en esta PC; sin esa opción el cliente Java negocia una API obsoleta y los tests se omiten.
- La advertencia unchecked de `FilteredQueryService`, el uso deprecated en `SecurityTests` y el auto-attach de Mockito siguen presentes.
- El adapter `local-log` no demuestra entrega real, ACK ni DLQ de broker.
- El mecanismo Core/JWT definitivo, CORS productivo y publicación de Swagger por ambiente dependen de contratos externos.
- Las métricas alcanzaron el límite de tags URI durante la prueba que recorre muchas rutas; revisar cardinalidad de observabilidad antes de producción.

## 13. Pendientes externos

- Core/JWT: issuer, JWKS/public key, claims, login/logout/me e invalidación.
- Broker: tecnología, topics/queues, schemas, versionado, ACK, retry y DLQ definitivos.
- M1/M2/M4/M7/M8: contrato final de eventos, ownership de datos, correlación y semántica de reproceso.

No se afirma que ninguna integración externa esté operativa. No se realizó push, merge ni despliegue.

## 14. POSTGRESQL 17 REAL VALIDATION

### Entorno Docker

- Docker Desktop 4.88.1; cliente y engine 29.7.2; API del servidor 1.55.
- Docker Compose v5.4.0.
- `docker info` operativo y `docker run --rm hello-world` exitoso.
- En Windows se usó `DOCKER_HOST=npipe:////./pipe/dockerDesktopLinuxEngine` y `-Dapi.version=1.44` por compatibilidad entre Testcontainers 1.21.3 y Docker Engine 29.

### PostgreSQL, Flyway e Hibernate

- Imagen `postgres:17-alpine`; versión efectiva PostgreSQL 17.11.
- Compose `backend-postgres-1`: healthy, puerto local 5432, base/user `rentas`, conexión aceptada por `pg_isready`.
- El volumen local era nuevo, por lo que la validación partió de un esquema vacío sin borrar datos ajenos.
- Flyway validó y aplicó V1, V2, V3, V4, V5, V6, V7, V8 y V9; no fue necesaria V10.
- Hibernate con `ddl-auto=validate` inició correctamente tanto desde Maven como en la imagen Compose.

### Tests y quality gate

- `PostgreSqlIntegrationTest`: 4 ejecutados, 4 aprobados, 0 skipped. Se observó el arranque real de Ryuk y `postgres:17-alpine`, conexión JDBC y Flyway V1-V9.
- Suite de concurrencia contra PostgreSQL 17: 4/4 (pagos sobre la misma deuda, doble imputación, doble plan y ejecución única de reversión/aplicación de crédito).
- `PaymentPlanFlowTests` contra PostgreSQL 17: 5/5.
- `BillingFlowTests` contra PostgreSQL 17: 6/6, incluido sobrepago `120 = 100 + 20` y bloqueo de doble gasto del crédito.
- Gate final `mvnw clean verify`: BUILD SUCCESS; 53 tests, 53 passed, 0 failures, 0 errors y 0 skipped.
- JaCoCo: líneas 87,83%, instrucciones 87,37%, branches 55,00%; gate de líneas >= 85% cumplido.

### Integridad e índices

- Metadata real confirmó `amount = allocated_amount + unallocated_amount`, no negatividad y el desglose `principal_applied + interest_applied = amount`.
- Se confirmaron `available_amount <= original_amount`, XOR de origen de deuda y XOR del destino de imputación.
- También se confirmaron checks de liquidación, ajuste, plan, cuota, exención, corrida, retry de integración y retry de Outbox.
- Se verificaron índices de deuda, pago, liquidación, plan, cuota, obligación externa, log de integración y Outbox, incluidas claves únicas de negocio/idempotencia.

### Arranque, health, OpenAPI y Compose

- Spring Boot arrancó contra PostgreSQL 17; Tomcat quedó operativo.
- `/actuator/health` y `/api/v1/health`: HTTP 200, `UP`.
- OpenAPI: HTTP 200, 122 paths y 136 operaciones; 0 rutas `/api/v1/events` o `/api/v1/events/**`.
- `docker compose --profile full up -d --build`: imagen construida; `backend-backend-1` y `backend-postgres-1` healthy.
- Después del smoke se recreó exclusivamente la base local `rentas`, Flyway dejó nuevamente V1-V9 (9/9) y se verificaron 0 contribuyentes, 0 pagos y 0 deudas.
- Limpieza final: `docker compose --profile full down` sin `-v`; no quedaron contenedores Compose/Testcontainers y se conservó el volumen `backend_rentas_postgres`. Docker Desktop quedó instalado y el engine operativo.

### Bugs encontrados durante PostgreSQL real

1. Hibernate podía hacer un flush intermedio de `Payment` después de aumentar `allocatedAmount` pero antes de disminuir `unallocatedAmount`; PostgreSQL rechazaba correctamente la fila por `ck_payment_consistency`. Se sincronizan ambos campos dentro de cada imputación y se agregó regresión Testcontainers.
2. El sobrepago se informaba como `allocated=120/unallocated=0` aunque sólo existía una imputación real de 100 y un crédito de 20. Ahora conserva `allocated=100/unallocated=20` y bloquea la imputación directa posterior para evitar doble gasto.

Los errores de constraint que aparecen en el log histórico de PostgreSQL corresponden a la reproducción deliberada del primer bug antes de corregirlo; el arranque y las validaciones finales no registraron fallos inesperados.
