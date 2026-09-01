# Guía de aprendizaje - cierre y hardening de M5

Este documento se creó sólo en esta PC. Explica qué se cambió en la etapa final, por qué se hizo y cómo podés comprobarlo.

## 1. De rutas funcionales a contratos completos

Una ruta que responde 200 no está necesariamente terminada. Para considerar completo un endpoint se revisaron juntos: filtros, paginación, orden, DTO de respuesta, roles, ownership, validaciones, estados, persistencia, errores uniformes, OpenAPI y pruebas.

El resultado se registra en `BACKEND_COMPLETENESS.md`: 137 operaciones documentadas, de las cuales 134 pertenecen localmente a M5 y están completas. Las tres de autenticación dependen de Core/JWT.

## 2. Un motor común de filtros

Los listados no necesitan un método de repositorio distinto por cada combinación posible. `FilteredQueryService` construye un `Specification` de JPA a partir de parámetros permitidos por recurso. Los repositorios de listado implementan `JpaSpecificationExecutor`.

La whitelist evita aceptar nombres arbitrarios de columnas y convierte un error de cliente en un 400 visible. El servicio convierte tipos (`Long`, enum, fecha, UUID), resuelve alias como `conceptId`, aplica rangos `from/to`, búsqueda `q`, paginación y orden.

```text
GET /api/v1/debts?taxpayerId=1&status=OVERDUE&page=0&size=20&sort=createdAt,desc
```

Aprendizaje: centralizar una regla repetida reduce código, pero cada recurso conserva su contrato mediante una definición explícita de campos.

## 3. DTO de respuesta versus entidad JPA

Una entidad representa almacenamiento; un DTO representa el contrato público. Exponer la entidad acopla la API al esquema, puede disparar relaciones lazy y deja escapar campos internos.

`ApiDtos` declara respuestas finales y `ApiResponses` concentra mapeos. Algunos campos son derivados, por ejemplo `DebtResponse.overdue`, `DebtResponse.inPaymentPlan`, `InstallmentResponse.overdue` y `ExemptionResponse.expired`. Spring serializa las páginas con una estructura estable.

## 4. Componentes de liquidación

Antes sólo quedaba el total. Ahora `LiquidationComponent` explica cómo se obtuvo:

```text
BASE - DISCOUNT - EXEMPTION - SOCIAL_BENEFIT + SURCHARGE + INTEREST = finalAmount
```

Cada fila conserva `type`, `sourceType`, `sourceId`, `description` y `amount`. El preview muestra el mismo desglose que luego se persiste. Las pruebas verifican tanto la suma como el origen de los componentes.

Aprendizaje: en sistemas tributarios no alcanza con guardar el resultado; hay que poder reconstruir la decisión económica.

## 5. Concurrencia y locks

`@Transactional` garantiza atomicidad, pero por sí solo no evita que dos transacciones lean el mismo saldo simultáneamente. Por eso las operaciones sensibles usan lecturas con lock pesimista:

- pago e imputación bloquean deuda, pago o cuota;
- otorgar un plan bloquea sus deudas y vuelve a comprobar planes activos;
- ejecutar una reversión bloquea reversión y pago;
- aplicar saldo a favor bloquea crédito y deuda.

`ConcurrencyTests` dispara pares de operaciones en paralelo y comprueba que nunca se sobreimpute, se gaste dos veces un pago o crédito, se otorguen dos planes activos ni se ejecute dos veces una reversión.

## 6. Envelope, idempotencia y Outbox

Los eventos entrantes usan un envelope con `eventId`, tipo, fecha, módulo origen y data. `ProcessedEvent` evita repetir una entrega; las obligaciones externas agregan una unique key de negocio para impedir duplicados aunque cambie el UUID.

Para salida, el caso de uso guarda `OutboxEvent` en la misma transacción que el cambio económico. El publicador procesa:

```text
PENDING -> PUBLISHED
PENDING/FAILED -> FAILED -> reintento
FAILED con límite agotado -> DEAD_LETTER
```

Se registran `retryCount`, `lastAttemptAt`, `errorMessage` y `publishedAt`. Un evento publicado no vuelve a seleccionarse.

## 7. PostgreSQL real con Testcontainers

H2 es rápido, pero no valida todos los detalles de PostgreSQL. `PostgreSqlIntegrationTest` levanta `postgres:17-alpine`, arranca el contexto, ejecuta Flyway y revisa checks, uniques e índices importantes.

```powershell
.\mvnw.cmd -Dtest=PostgreSqlIntegrationTest test
```

La anotación `disabledWithoutDocker=true` hace que la suite se omita limpiamente si Docker no está disponible. Un test omitido no equivale a uno aprobado: en esta PC debe repetirse cuando Docker Desktop esté instalado y activo.

## 8. Seguridad como borde reemplazable

El modo dev acepta headers para facilitar pruebas. La lógica de negocio consulta `CurrentIdentity`, no conoce el mecanismo de autenticación. En producción, Core debe emitir el JWT y el filtro real debe traducir claims a la misma identidad.

Los tests fijan dos reglas: `AUDITOR` no modifica y `TAXPAYER` sólo lee recursos propios. Swagger y `/actuator/health` son públicos.

## 9. Escenarios de flujo

Los escenarios pedidos se cubren sin duplicar suites:

- A: `DomainFlowTests.liquidationWithActiveConfigurationCreatesDebtAtomically` más las aserciones de componentes.
- B: `BillingFlowTests.overpaymentBecomesCreditAndIsNotCountedAsUnallocated`.
- C: `DomainFlowTests.overpaymentCreatesCreditAndApprovedReversalRestoresDebt`.
- D: `PaymentPlanFlowTests.installmentPaymentReducesOnlyItsPrincipalAndBlocksDirectDebtPayment`.
- E: `IntegrationFlowTests.failedM4ObligationCanBeRetriedAfterReferenceIsAvailable`.
- F: `DomainFlowTests.duplicateEventAndBusinessDuplicateCreateSingleDebt` y el reproceso del log original.

La etapa final agregó pruebas HTTP de filtros/DTO/componentes, concurrencia, fallo/retry de Outbox y PostgreSQL real condicional.

## 10. Verificación de punta a punta

```powershell
.\mvnw.cmd clean verify
```

Con Docker:

```powershell
Copy-Item .env.example .env
docker compose up -d postgres
.\mvnw.cmd spring-boot:run
```

Después abrí `/actuator/health`, `/swagger-ui.html` y `/v3/api-docs`. Finalmente revisá `target/site/jacoco/index.html` y `BACKEND_COMPLETENESS.md`.

La regla profesional es documentar por separado lo aprobado, lo omitido y lo bloqueado; nunca transformar una limitación del ambiente en un éxito ficticio.

## 11. Qué cambió en la segunda etapa de hardening

El quality gate ahora forma parte de Maven. No depende de que una persona recuerde mirar un HTML: `verify` falla si las líneas globales bajan de 85%. La medición final fue 87,83% sin excluir controllers, services ni lógica de negocio.

El sobrepago enseñó una diferencia importante entre *no imputado* y *convertido a crédito*. Si $120 cancelan una deuda de $100, los $20 restantes ya no están libres dentro del pago: se transformaron en un `CreditBalance`. Por eso el pago queda completamente asignado, aunque sólo exista una allocation contra deuda por $100.

Las cuotas agregaron `principalApplied` e `interestApplied`. Guardar ambos evita recalcular el pasado con una fórmula o configuración que podría cambiar. Al revertir, sólo `principalApplied` vuelve a la deuda original; el interés no era capital y no debe aumentarla.

## 12. Defensa en profundidad en la base

Las validaciones Java mejoran el error que recibe el usuario, pero una carrera, un script o un bug futuro puede evitarlas. V9 agrega checks equivalentes para los invariantes que deben ser siempre verdaderos. Esta combinación se llama defensa en profundidad:

```text
DTO valida formato -> servicio valida negocio -> lock serializa -> transacción agrupa -> DB impide estado imposible
```

No todas las reglas pertenecen a un CHECK. Por ejemplo, “una deuda no puede estar en dos planes activos” involucra filas y estados: se protege con transacción, lock, consulta y pruebas concurrentes.

## 13. Errores y correlación

El cliente recibe un error estable sin SQL ni stack trace. El detalle técnico sí queda en el log junto con `traceId`. `X-Correlation-Id` conecta request, respuesta, log y auditoría; si el cliente no aporta uno seguro, el backend genera un UUID. El `finally` del filtro elimina MDC porque los threads del servidor se reutilizan.

## 14. Resultado comprobable

La corrida final local detectó 52 tests: 49 aprobaron, 3 Testcontainers se omitieron, 0 fallaron y 0 terminaron con error. Flyway aplicó V1–V9 sobre H2 en modo PostgreSQL. Docker no está instalado, de modo que PostgreSQL 17 real sigue pendiente y no se presenta como validado.

El detalle auditable está en `BACKEND_HARDENING_REPORT.md`; esta guía explica el razonamiento para que puedas repetirlo y defender las decisiones.
