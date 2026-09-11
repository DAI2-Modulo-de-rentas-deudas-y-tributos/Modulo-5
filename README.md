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

## Automatización

El CI siempre valida la estructura DevOps y Terraform. Los jobs de frontend y
backend se activan automáticamente cuando detectan `package.json`/lockfile o
`pom.xml`, respectivamente.
