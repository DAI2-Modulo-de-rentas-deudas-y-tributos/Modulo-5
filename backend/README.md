# Módulo 5 - Rentas

Backend independiente en Spring Boot 3 y Java 17 para conceptos tributarios, liquidaciones, deudas, pagos, planes, exenciones, auditoría e integración asíncrona.

Estado local: las 134 operaciones REST que corresponden a M5 están implementadas. Las tres rutas de autenticación de la matriz (`login`, `logout`, `me`) quedan **PENDIENTES DE INTEGRACIÓN CORE/JWT**. La trazabilidad completa está en `BACKEND_COMPLETENESS.md`.

## Requisitos y verificación

- JDK 17.
- Docker Desktop sólo para PostgreSQL real, Testcontainers o Compose.
- Maven no necesita instalación: el proyecto incluye Maven Wrapper.

```powershell
.\mvnw.cmd clean verify
```

El build aplica un quality gate JaCoCo de cobertura global de líneas >= 85%. La suite común usa H2 en modo PostgreSQL. `PostgreSqlIntegrationTest` usa `postgres:17-alpine` y se omite limpiamente cuando Docker no está disponible:

```powershell
.\mvnw.cmd -Dtest=PostgreSqlIntegrationTest test
```

Con Docker Desktop/Engine 29 y Testcontainers 1.21.3 en Windows, la API mínima del engine es más nueva que el default del cliente Java. En esta PC se validó con:

```powershell
$env:DOCKER_HOST = 'npipe:////./pipe/dockerDesktopLinuxEngine'
.\mvnw.cmd -Dapi.version=1.44 clean verify
```

El reporte de cobertura queda en `target/site/jacoco/index.html`. Un test omitido por ausencia de Docker no valida PostgreSQL: debe repetirse localmente o en CI con el daemon disponible.

## Inicio local

```powershell
Copy-Item .env.example .env
docker compose up -d postgres
.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=dev
```

O backend y base juntos:

```powershell
docker compose --profile full up --build
```

PostgreSQL y backend tienen healthchecks; el backend espera a que PostgreSQL esté saludable.

- Swagger UI: http://localhost:8080/swagger-ui.html
- OpenAPI: http://localhost:8080/v3/api-docs
- Health: http://localhost:8080/actuator/health
- API: `http://localhost:8080/api/v1`

## Variables de entorno

`.env.example` contiene únicamente valores de ejemplo y defaults locales seguros. Copiarlo a `.env` no alcanza para un entorno compartido: hay que reemplazar cada `CHANGE_ME` con un secreto provisto por el equipo y mantener `.env` fuera de Git.

Docker Compose lee `.env` automáticamente. Una ejecución directa mediante Maven o el IDE no lo hace: en ese caso `DB_URL`, `DB_USER` y `DB_PASSWORD` deben exportarse en el proceso o configurarse en el IDE. El perfil inseguro de identidad simulada se habilita sólo de forma explícita con `-Dspring-boot.run.profiles=dev`; no debe utilizarse en producción.

| Variable | Uso | Requerida |
| --- | --- | --- |
| `POSTGRES_DB` | Nombre de la base creada por Compose. | No; default local `rentas`. |
| `POSTGRES_USER` | Usuario de PostgreSQL creado por Compose. | No; default local `rentas`. |
| `POSTGRES_PASSWORD` | Contraseña de PostgreSQL y del backend dentro de Compose. | Sí para Compose; guardar como secreto. |
| `POSTGRES_PORT` | Puerto de PostgreSQL publicado en el host. | No; default `5432`. |
| `DB_URL` | URL JDBC cuando Spring Boot se ejecuta directamente. | No si aplica el default local; configurar fuera de local. |
| `DB_USER` | Usuario JDBC cuando Spring Boot se ejecuta directamente. | No si aplica el default local; configurar fuera de local. |
| `DB_PASSWORD` | Contraseña JDBC cuando Spring Boot se ejecuta directamente. | Sí para una base que exija autenticación; guardar como secreto. |
| `SERVER_PORT` | Puerto HTTP directo o puerto publicado por Compose. | No; default `8080`. |
| `OUTBOX_DELAY_MS` | Intervalo entre ciclos de publicación del Outbox. | No; default `5000`. |
| `BROKER_ADAPTER` | Selector reservado del adaptador; hoy sólo existe `local-log`. | No; default `local-log`. |

Las variables de descarga del Maven Wrapper (`MVNW_REPOURL`, `MVNW_USERNAME`, `MVNW_PASSWORD`, `MAVEN_USER_HOME` y `MVNW_VERBOSE`) son opcionales y pertenecen a la herramienta de build, no a la configuración de ejecución de M5. `MVNW_PASSWORD`, si se usa con un repositorio Maven privado, debe configurarse como secreto de CI o del entorno local.

Core/JWT todavía no tiene propiedades ni variables implementadas. El broker real tampoco: no existen aún variables para bootstrap servers, tópicos, ACK, DLQ, schemas o credenciales. Se agregarán sólo cuando sus contratos y su implementación estén definidos.

## Seguridad

En perfil explícito `dev`, `DevIdentityFilter` permite probar con:

```text
X-Dev-User: nombre
X-Dev-Roles: RENTAS,SUPERVISOR
X-Dev-Taxpayer-Id: 1
```

Sin headers se utiliza una identidad local de empleado. Para probar ownership se usa rol `TAXPAYER` y su propio `X-Dev-Taxpayer-Id`. `AUDITOR` sólo puede leer. El perfil `dev` ya no se activa por defecto: sin un proveedor Core/JWT configurado el backend queda cerrado, no confía en headers falsos.

Cada solicitud recibe `X-Correlation-Id`; un valor entrante sólo se conserva si tiene formato seguro. El mismo identificador se usa en errores, logs y auditoría.

En ambientes reales debe sustituirse este borde por el JWT emitido por Core, conservando `CurrentIdentity` como puerto de acceso a usuario, roles y contribuyente. M5 no implementa usuarios ni contraseñas propias.

## Listados, filtros y respuestas

Los listados generales aceptan paginación y orden estándar:

```text
?page=0&size=20&sort=createdAt,desc
```

Cada recurso tiene una whitelist de filtros en `FilteredQueryService`. También se soportan `from`, `to` y `q` cuando corresponden. Un filtro u orden desconocido responde 400 con `INVALID_FILTER` o `INVALID_SORT`.

Las respuestas REST usan DTO explícitos; no serializan entidades JPA. Las páginas tienen forma estable con `content` y metadatos bajo `page`.

## Liquidación y trazabilidad monetaria

Cada liquidación persiste componentes `BASE`, `DISCOUNT`, `EXEMPTION`, `SOCIAL_BENEFIT`, `SURCHARGE` e `INTEREST`. Cada fila guarda tipo, origen, identificador de origen, descripción e importe. La suma coincide con el importe final y queda disponible en preview y consulta.

## Eventos y Outbox

No hay endpoints `/events/*`: un adapter de broker debe entregar `EventEnvelope` a los handlers locales. La recepción registra `eventId`, payload, módulo origen/destino, timestamps, resultado e idempotencia técnica y de negocio.

El Outbox usa `PENDING`, `FAILED`, `PUBLISHED` y `DEAD_LETTER`, con contador de reintentos, último intento, error y fecha de publicación. El adapter actual (`BROKER_ADAPTER=local-log`) sólo publica al log.

Antes de conectar Kafka/RabbitMQ siguen pendientes de acuerdo externo: broker, tópicos/colas, schemas versionados, ACK, política de retry y DLQ. Esto no se presenta como integración productiva.

## Arquitectura

- `ApiController`: contrato HTTP, ownership y roles.
- `ApiDtos` / `ApiResponses`: entrada y salida estable.
- `FilteredQueryService`: filtros, rangos, paginación y sorting.
- `DomainServices`: reglas y límites transaccionales.
- `DomainEntities` / `Repositories`: persistencia e invariantes.
- `integration/event`, `consumer`, `mapper`, `producer` y `validation`: contratos confirmados M1/M2/M8, normalización y Outbox desacoplado.
- `IntegrationServices`: adapters genéricos existentes y publicación abstracta.
- `db/migration`: doce migraciones Flyway.

M5 no consulta bases de M1, M2, M4, M7 ni M8: conserva referencias locales y se integra por mensajes.

## Documentación local

- `BACKEND_COMPLETENESS.md`: matriz endpoint por endpoint y reporte OpenAPI.
- `GUIA_APRENDIZAJE.md`: explicación progresiva del backend.
- `GUIA_FINAL_HARDENING.md`: explicación específica de esta etapa de cierre.
- `INTEGRATION_CONTRACTS.md`: contratos confirmados M1/M2/M7/M8 y pendientes M4/M7 outbound/Core/Broker.
- `M7_CONTRACT_AND_API_AUDIT.md`: adaptación contractual de Tránsito e inventario auditado de la API REST.
- `BACKEND_HARDENING_REPORT.md`: baseline, decisiones, evidencias y riesgos restantes.
- `API_PERFORMANCE_HARDENING_REPORT.md`: cierre de los hallazgos GET/POST, evidencia de consultas y decisiones de compatibilidad.

## Problemas frecuentes

- `JAVA_HOME ... not defined`: configurar JDK 17, no un JRE.
- `docker: command not found`: instalar/iniciar Docker Desktop; los tests Testcontainers se omitirán mientras tanto.
- Testcontainers omitido con Docker Engine 29 operativo: usar la configuración `DOCKER_HOST` y `-Dapi.version=1.44` indicada arriba.
- Puerto 5432 ocupado: cambiar `POSTGRES_PORT` y `DB_URL`.
- 403: revisar roles; `AUDITOR` no escribe y `TAXPAYER` no accede a recursos ajenos.
