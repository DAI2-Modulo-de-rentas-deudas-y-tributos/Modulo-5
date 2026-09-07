# Módulo 5 - Rentas

Backend independiente en Spring Boot 3 y Java 17 para conceptos tributarios, liquidaciones, deudas, pagos, planes, exenciones, auditoría e integración asíncrona.

Estado local: la API incluye operaciones de dominio, autenticación DEMO/DEV persistente, recargos, procesamiento de vencimientos y conciliación electrónica. Core/JWT productivo permanece **PENDIENTE**; la autenticación DEMO no lo reemplaza.

## Docker en Windows, Linux y macOS (Intel o Apple Silicon)

El Dockerfile usa `eclipse-temurin:17-jdk-jammy` y `17-jre-jammy`,
con variantes nativas AMD64 y ARM64. Docker selecciona la arquitectura del equipo;
no agregar `platform: linux/amd64` para un Mac con Apple Silicon.
Se mantiene Java 17 y la ejecución con usuario sin privilegios.

Con Docker Desktop iniciado, desde la raíz del repositorio (donde está
`compose.yaml`), y con la configuración local de PostgreSQL ya preparada:

```sh
docker compose --profile application up -d --build postgres backend
docker compose --profile application ps
docker compose logs --tail=100 backend
curl --fail http://localhost:8080/actuator/health
```

El healthcheck debe devolver `UP`. Si se configuró otro `BACKEND_PORT`,
utilizar ese puerto. No hace falta instalar Java o Maven en el host para esta opción.
La primera construcción descarga las imágenes y dependencias.
No borrar volúmenes para resolver un problema de arquitectura o contraseña:
una base ya inicializada conserva sus credenciales.

Para ejecutar Maven directamente en macOS, con JDK 17 instalado, usar desde
`backend/`: `sh ./mvnw spring-boot:run` (no `mvnw.cmd` ni `npm run dev`).
Esta alternativa requiere configurar previamente la conexión a PostgreSQL.

### Compañero con Mac (Apple Silicon o Intel)

Requisitos: Docker Desktop, Git, y Node 20+ si el frontend corre fuera de Docker.

Desde la raíz del repositorio, con Docker Desktop iniciado. Copiar `.env.example`
a `.env` (no commitear el `.env` real) y poner `RENTAS_SECURITY_DEV_MODE=true`
sólo en esa copia local:

```sh
docker compose --profile application up -d --build postgres backend
```

Frontend en otra terminal:

```sh
cd frontend
cp .env.example .env
npm install
npm run dev
```

El `.env` de frontend de ejemplo full-stack usa:

```text
VITE_USE_MOCKS=false
VITE_AUTH_MODE=mock
VITE_DEV_IDENTITY_HEADERS=true
VITE_API_BASE_URL=http://localhost:8080
```

Los usuarios se crean en PostgreSQL (`POST /api/v1/dev-auth/users` con identidad
SUPERVISOR DEV). El login del frontend llama a `POST /api/v1/dev-auth/login`.
No hay usuarios hardcodeados en el runtime del frontend.

Arquitectura de las imágenes base: `eclipse-temurin:17-*-jammy` tiene manifest
AMD64 y ARM64. En una PC AMD64 el build local no demuestra runtime ARM64:

- `AMD64_LOCAL_BUILD` — se valida al construir en Windows/Linux AMD64.
- `ARM64_BASE_IMAGES=SUPPORTED` — las bases oficiales incluyen linux/arm64.
- `APPLE_SILICON_RUNTIME=REQUIRES_MAC_SMOKE` — falta una pasada en Mac.

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
docker compose --profile application up -d --build postgres backend
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
| `RENTAS_SECURITY_DEV_MODE` | Habilita identidad y endpoints DEMO/DEV. | No; default `false`. Prohibido en producción. |
| `RENTAS_DEMO_BOOTSTRAP_PASSWORD` | Contraseña común de bootstrap para cinco usuarios locales, almacenada sólo como hash BCrypt. | No; sin default. Secreto local/CI efímero. |

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

Con `RENTAS_DEMO_BOOTSTRAP_PASSWORD` definido se crean `demo.rentas`, `demo.supervisor`, `demo.caja`, `demo.auditoria` y `demo.contribuyente`; el valor real no se documenta. También pueden administrarse por `/api/v1/dev-auth/users`. Las contraseñas se guardan con BCrypt y `TAXPAYER` exige un `taxpayerId` existente.

En ambientes reales debe sustituirse este borde por el JWT emitido por Core, conservando `CurrentIdentity` como puerto de acceso a usuario, roles y contribuyente. Las tablas y rutas `demo_*` son únicamente una facilidad local explícita.

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

No hay endpoints productivos `/events/*`: un adapter de broker debe entregar `EventEnvelope` a los handlers locales. La recepción registra `eventId`, payload, módulo origen/destino, timestamps, resultado e idempotencia técnica y de negocio.

### Simulación local de eventos de tickets de M2

Mientras el broker real no esté conectado, QA puede entregar `ticketCreated` y `ticketUpdated`
al mismo consumidor mediante un adaptador HTTP exclusivo del modo de desarrollo. El endpoint
no se registra cuando `RENTAS_SECURITY_DEV_MODE=false`.

Con PostgreSQL y el backend local iniciados con `RENTAS_SECURITY_DEV_MODE=true`:

```powershell
$headers = @{
  "X-Dev-User" = "qa-supervisor"
  "X-Dev-Roles" = "SUPERVISOR"
}

$createdEventId = [guid]::NewGuid().ToString()
$created = @{
  eventId = $createdEventId
  eventType = "ticketCreated"
  occurredAt = "2026-09-03T10:00:00-03:00"
  sourceModule = "M2"
  data = @{
    ticketId = 1001
    citizenId = 123
    category = "RENTAS"
    description = "El pago no aparece imputado"
    priority = "HIGH"
  }
} | ConvertTo-Json -Depth 5

Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8080/api/v1/dev/integrations/m2/events" `
  -Headers $headers -ContentType "application/json" -Body $created
```

Repetir exactamente el mismo body permite verificar la idempotencia por `eventId`: se devuelve
el ticket existente y no se duplica el caso ni su evidencia de procesamiento.

```powershell
$updatedEventId = [guid]::NewGuid().ToString()
$updated = @{
  eventId = $updatedEventId
  eventType = "ticketUpdated"
  occurredAt = "2026-09-03T10:05:00-03:00"
  sourceModule = "M2"
  data = @{
    ticketId = 1001
    additionalInformation = "El ciudadano adjuntó información"
  }
} | ConvertTo-Json -Depth 5

Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8080/api/v1/dev/integrations/m2/events" `
  -Headers $headers -ContentType "application/json" -Body $updated
```

El resultado se consulta en `GET /api/v1/tickets` y la evidencia técnica en
`GET /api/v1/integrations/events`. Para tomar el ticket, un agente con rol `RENTAS` ejecuta
`POST /api/v1/tickets/{id}/assign`: el caso pasa a `IN_PROGRESS` y M5 agrega
`updateTicketStatus` al transactional outbox para M2.

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
- `db/migration`: catorce migraciones Flyway (V1–V14).

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
