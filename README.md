# Módulo 5 - Rentas

Monorepo del módulo de Rentas, tributos, deudas y planes de pago.

## Componentes

- `frontend/`: aplicación web.
- `backend/`: API y procesos asíncronos.
- `contracts/`: contratos y esquemas de eventos.
- `infra/`: infraestructura AWS administrada con Terraform.
- `docs/`: arquitectura y decisiones técnicas.

## Ambientes

- `dev`: integración continua desde la rama `develop`.
- `test`: promoción controlada del mismo artefacto validado en `dev`.

La rama `main` representa la versión estable. El desarrollo se realiza en ramas
`feature/*` mediante pull requests hacia `develop`.

## Estado

El proyecto se encuentra en fase de inicio. Las tecnologías específicas de
frontend y backend se documentarán antes de generar sus aplicaciones base.

## Desarrollo local

El archivo `compose.yaml` levanta PostgreSQL para desarrollo local:

```shell
docker compose up -d postgres
```

Copiar `.env.example` a `.env` antes de personalizar credenciales locales.

## Infraestructura

Terraform utiliza configuraciones independientes para `dev` y `test`. El estado
remoto, OIDC de GitHub y roles de despliegue se crearán desde `infra/bootstrap/`.

Consultar [infra/README.md](infra/README.md) antes de ejecutar Terraform.
