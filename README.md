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

## Desarrollo local

PostgreSQL puede iniciarse aun cuando el código de aplicación todavía no esté
presente. Primero copie `.env.example` a `.env` y asigne un valor no vacío a
`POSTGRES_PASSWORD`:

```powershell
docker compose up -d postgres
```

Cuando el backend sea incorporado:

```powershell
docker compose --profile application up --build
```

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

El CI siempre valida la estructura DevOps y Terraform. Los jobs de frontend y
backend se activan automáticamente cuando detectan `package.json`/lockfile o
`pom.xml`, respectivamente.

## Continuidad del traspaso técnico

La documentación del Product Owner está versionada en [`docs/handoff/`](docs/handoff/).
Para distinguir los reportes históricos de la evidencia comprobada en esta rama,
consultar [`ESTADO_VERIFICADO_P0_P1_2026-10-26.md`](docs/handoff/ESTADO_VERIFICADO_P0_P1_2026-10-26.md).
