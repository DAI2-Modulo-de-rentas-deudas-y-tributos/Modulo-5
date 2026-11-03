# Módulo 5 - Rentas

Monorepo del módulo de Rentas, tributos, deudas y planes de pago.

## Componentes

- `frontend/`: aplicación web React/Vite del Módulo 5.
- `backend/`: API Spring Boot con persistencia PostgreSQL/Flyway.
- `contracts/`: contratos y esquemas de eventos.
- `infra/`: infraestructura AWS administrada con Terraform.
- `docs/`: arquitectura y decisiones técnicas.

El modo full-stack usa PostgreSQL como fuente de verdad. El frontend no contiene
un dataset alternativo de producción: todas las operaciones pasan por la API.

## Patrones, seguridad y evidencia

- [Auditoría SOLID y patrones M5](docs/AUDITORIA_SOLID_M5.md)
- [Criterios verificables de seguridad](docs/SEGURIDAD_CRITERIOS_ACEPTACION.md)
- [Escaneo de secretos en el historial (G6)](docs/security/G6_ESCANEO_SECRETOS.md)
- [Matriz de flujos frontend/backend](docs/M5_FLUJOS_FRONTEND_BACKEND_EVIDENCIA.md)
- [Niveles y bloqueos de integración](docs/M5_NIVELES_INTEGRACION.md)
- [Guía de exposición y demo segura](docs/GUIA_EXPOSICION_PATRONES_SEGURIDAD.md)
- [Índice de ADR](docs/adr/README.md)
- [Estado local de integraciones y migraciones pendientes](docs/integrations/M5_ESTADO_LOCAL_2026-10-30.md)

## Tecnologías acordadas

- JavaScript, React y Vite para el frontend;
- Java 17 y Spring Boot para el backend;
- PostgreSQL 17 y Flyway para persistencia y migraciones;
- Docker para empaquetar el backend;
- AWS Amplify Hosting para el frontend web;
- ECS Fargate para el backend en etapas posteriores.

## Ambientes

- `dev`: integración continua desde la rama `develop`.
- `test`: promoción controlada del mismo artefacto validado en `dev`.
- `prod`: persistente; se despliega automáticamente en cada merge o commit a `main`
  ([ADR 0003](docs/adr/0003-ambiente-produccion.md)).

La rama `main` representa la versión estable. El desarrollo se realiza en ramas
`feature/*` mediante pull requests hacia `develop`.

## Levantar el sistema completo

Requisitos: Git y Docker Desktop (o Docker Engine con Compose v2) iniciado. No hace
falta Java, Maven ni Node en el equipo. El primer arranque descarga imágenes y
dependencias y compila backend y frontend: tarda varios minutos.

1. Clonar el repositorio y, desde su raíz, crear el `.env` a partir del ejemplo:

   ```shell
   cp .env.example .env
   ```

   En PowerShell: `Copy-Item .env.example .env`.

2. Abrir `.env` y completar las cuatro variables marcadas como **OBLIGATORIA**. Valen
   valores propios cualquiera, de 8 o más caracteres, sin espacios, comillas ni barras
   invertidas:

   | Variable | Para qué se usa |
   |---|---|
   | `POSTGRES_PASSWORD` | Contraseña de la base PostgreSQL. |
   | `RABBITMQ_PASSWORD` | Contraseña del broker RabbitMQ (usuario `m5-local`). |
   | `RENTAS_DEMO_BOOTSTRAP_PASSWORD` | Secreto interno para crear el primer usuario. |
   | `RENTAS_DEMO_USERS_PASSWORD` | Contraseña de ingreso de todos los usuarios de la tabla de abajo. |

   El resto de las variables son opcionales y ya tienen valor. Si falta el `.env` o
   alguna obligatoria, el arranque se corta con `Falta <VARIABLE> en .env`.

3. Levantar todo con un único comando:

   ```shell
   docker compose up -d --build
   ```

   El comando termina cuando la base, el broker, el backend y los usuarios están
   listos; el frontend responde unos segundos después (`docker compose ps` lo muestra
   `healthy`). Si algo falla, indica qué servicio; sus logs se ven con
   `docker compose logs <servicio>`.

4. Abrir <http://localhost:5173> e ingresar con alguno de estos usuarios. La
   contraseña de todos es el valor que se puso en `RENTAS_DEMO_USERS_PASSWORD`:

   | Usuario | Perfil |
   |---|---|
   | `supervisor` | Supervisor (también opera como Personal de Rentas) |
   | `rentas` | Personal de Rentas |
   | `cajero` | Cajero |
   | `auditor` | Auditor |
   | `contribuyente` | Contribuyente, vinculado a "QA Ciudadano A" |

5. Opcional: comprobar que el frontend responde con sus cabeceras de seguridad (CSP,
   `X-Frame-Options`, `nosniff`, `Referrer-Policy`) y que los cinco usuarios ingresan, con
   el mismo chequeo que corre el CI (en Windows, desde Git Bash):

   ```shell
   sh scripts/compose/smoke-test.sh
   ```

Qué queda corriendo:

| Servicio | Dirección | Notas |
|---|---|---|
| Frontend | <http://localhost:5173> | Build de producción; reenvía `/api` al backend. |
| Backend | <http://localhost:8080/api/v1> | Swagger en <http://localhost:8080/swagger-ui.html>. |
| PostgreSQL | `localhost:5432` | Base `rentas`, usuario `rentas`. |
| RabbitMQ | <http://localhost:15672> | Usuario `m5-local`. Lo publicado por M5 queda en `q.m5.publicados`; la entrada es `q.rentas` y lo rechazado va a `q.rentas.dlq`. |

### Datos de demostración

No hay datos mock: todo sale de PostgreSQL. En el primer arranque, `demo-setup` deja la base
lista para recorrer el flujo principal, cargando los datos por la API como lo haría el personal:

| Qué | Detalle |
|---|---|
| Contribuyentes | QA Ciudadano A y QA Ciudadano B (ciudadanos) y QA Organización M4, de [`qa/seed-e2e-local.sql`](qa/seed-e2e-local.sql). |
| Configuración de tributos | `ABL` (0,15 % de la valuación, mínimo $5.000), `TASA_SERVICIOS` (fija, $12.000) y `PATENTE` (0,4 % de la valuación), vigentes desde el 1 de enero del año anterior. Las carga `rentas` y las aprueba `supervisor`. |
| Plan de pagos | Una configuración de 2 a 12 cuotas, con 10 % de anticipo mínimo e `interestRate` 2.5. |
| Deudas | 6 liquidaciones del mes anterior (una de ABL de dos meses atrás para QA Ciudadano A, ya vencida). Las fechas se calculan al arrancar. |
| Boleta | Una boleta de QA Ciudadano A (el usuario `contribuyente`) con sus dos deudas por vencer. |

La deuda vencida figura pendiente hasta procesar los vencimientos (**Rentas → Deudas → Procesar
vencimientos**), que es un paso de la demo. Los usuarios, la topología del broker y estos datos
los crean los servicios `broker-setup` y `demo-setup` ([`scripts/compose/`](scripts/compose/)),
sólo si faltan: las deudas y la boleta se cargan una vez y no se repiten en los arranques siguientes.

Otros comandos útiles:

```shell
docker compose ps -a          # estado de cada servicio
docker compose stop rabbitmq  # apagar el broker (prueba de resiliencia); volver con start
docker compose down           # detener todo, conservando los datos
docker compose down -v        # detener todo y borrar los datos (base y broker)
```

Si se cambian las contraseñas del `.env` después del primer arranque, hay que borrar
los datos con `docker compose down -v` antes de volver a levantar.

Para desarrollar con recarga en caliente se puede levantar sólo la base
(`docker compose up -d postgres`) y correr backend y frontend fuera de Docker; ver
[`backend/README.md`](backend/README.md) y [`frontend/README.md`](frontend/README.md).

### Variables de entorno de integración

- `VITE_API_BASE_URL`: URL pública del backend; no contiene secretos.
- `VITE_AUTH_MODE`: `mock` valida usuario/contraseña contra
  `POST /api/v1/dev-auth/login` (PostgreSQL + BCrypt + sesión opaca); `core` queda
  reservado para el contrato Core/JWT futuro.
- `RENTAS_SECURITY_DEV_MODE`: habilita endpoints y sesiones DEMO sólo para
  integración local. Default `false`; no debe habilitarse en producción.
- `RENTAS_DEMO_BOOTSTRAP_PASSWORD`: secreto de `POST /api/v1/dev-auth/bootstrap`
  para crear el primer SUPERVISOR cuando la tabla está vacía. No tiene default y
  nunca debe guardarse en Git.

Para datos reales con autenticación demo use `VITE_AUTH_MODE=mock` y
`RENTAS_SECURITY_DEV_MODE=true`. El frontend envía
`X-Demo-Session`. Con `VITE_API_BASE_URL` vacío, el proxy Vite enruta `/api` al
backend local. En DEV/TEST, la configuración inyecta el origen exacto del frontend
mediante `CORS_ALLOWED_ORIGINS`.

La autenticación de ese modo persiste usuarios y sesiones en PostgreSQL. No
reemplaza Core/JWT: con dev-mode desactivado, `/api/v1/dev-auth/*` no está disponible.
El perfil productivo debe ser `prod`; rechaza el modo demo aunque una variable de
entorno intente habilitarlo y no utiliza sus tablas ni repositorios.

Los ambientes `dev` y `test` usan el modo demo a propósito mientras Core/JWT siga
pendiente: es el único borde de identidad disponible para operar y para la
aceptación automatizada. `prod`, desplegado desde `main`, corre con el perfil `prod`
y `VITE_AUTH_MODE=core`, así que no admite inicios de sesión hasta integrar
Core/JWT. El esquema DEMO no puede viajar hasta ahí porque el perfil `prod` excluye
sus servicios, repositorios y validación Hibernate,
la migración `V18` retira las tablas y dos precondiciones de Terraform cortan el plan
si alguien intenta activar el modo demo o un secreto `RENTAS_DEMO_*` bajo ese perfil.
Esa garantía está cubierta por `ProductionSchemaIsolationTest` y
`ProductionSecurityBoundaryTest`, que corren en cada build.

## Automatización

El CI (`.github/workflows/ci.yml`) corre en cada PR y push a `develop` y `main`:
tests de frontend y backend, validación de Terraform, arranque completo con
`docker compose`, escaneo de dependencias vulnerables (OSV-Scanner) y de secretos. El check `ci-required` exige que todos
terminen bien y es el que bloquea el merge.

Sobre el sistema levantado por `docker compose`, el job `compose` también corre (scripts en `scripts/compose/`):

| Prueba | Script | Criterio |
|---|---|---|
| E2E con Playwright, accesibilidad (axe) y responsive | `e2e-test.sh` ([frontend/e2e/README.md](frontend/e2e/README.md)) | Eje A |
| Resiliencia: duplicados, DLQ y broker caído | `resilience-test.sh` | Eje C |
| Contrato contra el OpenAPI (Schemathesis) | `contract-test.sh` | Eje B |

Las que tardan más que los 15 minutos del pipeline corren en workflows propios, semanalmente, al
crear un tag `v*` y a pedido (Actions → *Run workflow*), con sus reportes como artefactos:

| Workflow | Qué corre | Criterio |
|---|---|---|
| `mutation.yml` | PIT sobre las 14 clases de dominio; falla debajo de 60 %. | Eje B |
| `no-funcionales.yml` | k6 (50 usuarios, 5 min, p95 < 500 ms, < 1 % de errores) y OWASP ZAP (0 alertas High). | Ejes C y D |
| `estabilidad.yml` | La suite completa 3 veces sobre el mismo commit. | Eje B |

## Continuidad del traspaso técnico

La documentación del Product Owner está versionada en [`docs/handoff/`](docs/handoff/).
Para distinguir los reportes históricos de la evidencia comprobada en esta rama,
consultar [`ESTADO_VERIFICADO_P0_P1_2026-10-26.md`](docs/handoff/ESTADO_VERIFICADO_P0_P1_2026-10-26.md).
