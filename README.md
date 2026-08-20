# Módulo 5 - Rentas

Monorepo del módulo de Rentas, tributos, deudas y planes de pago.

## Componentes

- `frontend/`: espacio reservado para la aplicación web React.
- `backend/`: espacio reservado para la aplicación Spring Boot.
- `contracts/`: contratos y esquemas de eventos.
- `infra/`: infraestructura AWS administrada con Terraform.
- `docs/`: arquitectura y decisiones técnicas.

El código de frontend y backend será implementado por el equipo de desarrollo.
La configuración DevOps define los contratos necesarios para probar, construir
y desplegar esas aplicaciones cuando se incorporen.

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

## Automatización

El CI siempre valida la estructura DevOps y Terraform. Los jobs de frontend y
backend se activan automáticamente cuando detectan `package.json`/lockfile o
`pom.xml`, respectivamente.
