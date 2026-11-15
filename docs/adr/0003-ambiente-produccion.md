# ADR 0003: Ambiente de producción desde `main`

- Estado: aceptado
- Fecha: 2026-10-28

## Contexto

Hasta ahora `main` era solo la rama estable: DEV es efímero y TEST se promueve a
mano. Se necesita un ambiente PROD persistente que refleje `main` sin
intervención manual y con datos propios.

## Decisión

- `infra/environments/prod` es una composición más de los mismos módulos, con
  estado remoto propio (`environments/prod/terraform.tfstate`), VPC `10.70.0.0/16`
  y una **base PostgreSQL nueva**, exclusiva de PROD: `deletion_protection`,
  7 días de backups y snapshot final si alguna vez se elimina.
- `cd-prod.yml` corre en cada push a `main` que toque código o infra: ejecuta el
  CI completo (`ci.yml` como `workflow_call`), aplica Terraform y despliega
  backend y frontend con los mismos workflows reutilizables que DEV
  (`cd-backend.yml`, `cd-frontend.yml`).
- **Cambios durante un deploy**: `concurrency: prod` con
  `cancel-in-progress: false`. El deploy en curso nunca se interrumpe, porque
  cortar un `terraform apply` o una migración Flyway deja el ambiente a medias.
  Los commits que llegan mientras tanto quedan en espera y GitHub conserva solo
  el más reciente: los intermedios se cancelan sin desplegarse, y el último ya
  los contiene.
- La imagen del backend se construye desde el commit de `main`. No se promueve
  desde DEV porque un merge commit o un commit directo a `main` no tiene imagen
  previa con ese SHA; el CI del mismo commit es la compuerta.
- PROD usa el perfil `prod`: sin modo demo ni secretos `RENTAS_DEMO_*`, y el
  frontend con `VITE_AUTH_MODE=core`. Las precondiciones del módulo ECS lo
  garantizan.
- Rol OIDC propio (`modulo-5-rentas-github-prod-deploy`), asumible solo desde el
  GitHub Environment `prod`, que debe aceptar únicamente la rama `main`. Los roles
  DEV y TEST reciben una denegación explícita sobre todo recurso con
  `Environment=prod`, porque sus permisos de RDS, ALB y VPC son sobre `*`.
- Un backend fallido no queda en verde: con el circuit breaker ECS vuelve a la
  revisión anterior y el servicio igual queda estable, así que el workflow
  verifica que la revisión primaria sea la recién registrada.

## Alternativas

- `cancel-in-progress: true`: despliega antes el último commit, pero puede cortar
  un apply o una migración en curso. Descartado.
- Sin concurrencia: dos deploys simultáneos compiten por el lock del estado y por
  el servicio ECS. Descartado.
- Promover la imagen validada en TEST: conserva el artefacto inmutable, pero exige
  un paso manual y no cubre commits directos a `main`. Descartado para PROD
  automático.
- Workspaces de Terraform: contradicen el modelo de estado por ambiente.

## Consecuencias

- Hasta integrar Core/JWT, PROD responde pero nadie puede iniciar sesión: el
  perfil `prod` no expone `/api/v1/dev-auth/*` y el modo `core` del frontend aún no
  autentica.
- Las migraciones Flyway corren al iniciar la tarea nueva mientras la anterior
  sigue atendiendo, así que deben ser compatibles hacia atrás (expandir y luego
  contraer).
- Costo fijo continuo (RDS, ALB, Fargate e IPv4 públicas), del orden de USD 50 por
  mes; PROD no tiene apagado nocturno.
- Destruir PROD requiere desactivar antes la protección de RDS y del ALB.
- Cada push a `main` que toque código corre el CI dos veces: el propio y el de la
  compuerta de `cd-prod.yml`.
