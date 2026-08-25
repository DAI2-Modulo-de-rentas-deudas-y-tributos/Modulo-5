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

La suite común usa H2 en modo PostgreSQL. `PostgreSqlIntegrationTest` usa `postgres:17-alpine` y se omite limpiamente cuando Docker no está disponible:

```powershell
.\mvnw.cmd -Dtest=PostgreSqlIntegrationTest test
```

El reporte de cobertura queda en `target/site/jacoco/index.html`.

## Inicio local

```powershell
Copy-Item .env.example .env
docker compose up -d postgres
.\mvnw.cmd spring-boot:run
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

## Seguridad

En perfil `dev`, `DevIdentityFilter` permite probar con:

```text
X-Dev-User: nombre
X-Dev-Roles: RENTAS,SUPERVISOR
X-Dev-Taxpayer-Id: 1
```

Sin headers se utiliza una identidad local de empleado. Para probar ownership se usa rol `TAXPAYER` y su propio `X-Dev-Taxpayer-Id`. `AUDITOR` sólo puede leer.

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
- `IntegrationServices`: handlers idempotentes y Outbox.
- `db/migration`: ocho migraciones Flyway.

M5 no consulta bases de M1, M2, M4, M7 ni M8: conserva referencias locales y se integra por mensajes.

## Documentación local

- `BACKEND_COMPLETENESS.md`: matriz endpoint por endpoint y reporte OpenAPI.
- `GUIA_APRENDIZAJE.md`: explicación progresiva del backend.
- `GUIA_FINAL_HARDENING.md`: explicación específica de esta etapa de cierre.

## Problemas frecuentes

- `JAVA_HOME ... not defined`: configurar JDK 17, no un JRE.
- `docker: command not found`: instalar/iniciar Docker Desktop; los tests Testcontainers se omitirán mientras tanto.
- Puerto 5432 ocupado: cambiar `POSTGRES_PORT` y `DB_URL`.
- 403: revisar roles; `AUDITOR` no escribe y `TAXPAYER` no accede a recursos ajenos.
