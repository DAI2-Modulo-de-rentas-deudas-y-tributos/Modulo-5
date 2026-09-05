# Módulo 5 - Rentas

Monorepo del módulo de Rentas, tributos, deudas y planes de pago.

## Componentes

- `frontend/`: aplicación web React/Vite del Módulo 5.
- `backend/`: API Spring Boot con persistencia PostgreSQL/Flyway.
- `contracts/`: contratos y esquemas de eventos.
- `infra/`: infraestructura AWS administrada con Terraform.
- `docs/`: arquitectura y decisiones técnicas.

El modo full-stack usa PostgreSQL como fuente de verdad y no recurre al dataset
frontend cuando `VITE_USE_MOCKS=false`.

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

La rama `main` representa la versión estable. El desarrollo se realiza en ramas
`feature/*` mediante pull requests hacia `develop`.

## Desarrollo local

PostgreSQL puede iniciarse aun cuando el código de aplicación todavía no esté
presente:

```powershell
docker compose up -d postgres
```

Cuando el backend sea incorporado:

```powershell
docker compose --profile application up --build
```

### Variables de entorno de integración

- `VITE_API_BASE_URL`: URL pública del backend; no contiene secretos.
- `VITE_USE_MOCKS`: `true` usa el dataset offline; `false` usa HTTP real.
- `VITE_AUTH_MODE`: `mock` conserva el login demo; `core` queda reservado para el
  contrato Core/JWT futuro.
- `VITE_DEV_IDENTITY_HEADERS`: envía `X-Dev-*` sólo con auth mock y habilitación
  explícita. Default `false`.
- `RENTAS_SECURITY_DEV_MODE`: habilita el puente de identidad sólo para integración
  local. Default `false`; no debe habilitarse en producción.
- `RENTAS_DEMO_BOOTSTRAP_PASSWORD`: secreto exclusivamente local que, si se define
  junto con dev-mode, crea usuarios DEMO para los cinco roles. No tiene default y
  nunca debe guardarse en Git.

Para datos reales con autenticación demo use `VITE_USE_MOCKS=false`,
`VITE_AUTH_MODE=mock`, `VITE_DEV_IDENTITY_HEADERS=true` y
`RENTAS_SECURITY_DEV_MODE=true`. Con `VITE_API_BASE_URL` vacío, el proxy Vite
enruta `/api` al backend local. CORS de deployment queda pendiente de la URL final.

La autenticación de ese modo se valida contra `demo_user` en PostgreSQL con BCrypt.
No reemplaza Core/JWT: con dev-mode desactivado, `/api/v1/dev-auth/*` no está disponible.

## Automatización

El CI siempre valida la estructura DevOps y Terraform. Los jobs de frontend y
backend se activan automáticamente cuando detectan `package.json`/lockfile o
`pom.xml`, respectivamente.
