# Infraestructura

Infraestructura AWS del módulo, administrada exclusivamente con Terraform.

## Organización

- `bootstrap/`: estado remoto, OIDC y roles de GitHub Actions.
- `modules/network/`: VPC y subredes reutilizables.
- `modules/ecs/`: ECR, ECS Cluster y grupo de logs.
- `environments/dev/`: composición desplegada automáticamente desde `develop`.
- `environments/test/`: composición reservada para la promoción a TEST.

Cada ambiente utiliza un estado remoto independiente. No se usan Terraform
workspaces para representar ambientes.

## Flujo de DEV

1. El Pull Request ejecuta formato y validación sin conectarse a AWS.
2. El bootstrap se aplica localmente si el PR modifica permisos IAM.
3. Después del merge a `develop`, `cd-dev.yml` asume el rol DEV mediante OIDC.
4. El pipeline inicializa el estado remoto, genera el plan y lo aplica.
5. Los outputs quedan visibles en el resumen de la ejecución de GitHub Actions.

## Fundación actual

La primera entrega crea solamente recursos sin carga de trabajo permanente:

- VPC, Internet Gateway y subredes en dos zonas de disponibilidad;
- repositorio ECR del backend;
- cluster ECS sin servicios ni tareas;
- grupo de logs con retención de 14 días.

RDS, ALB, tareas Fargate y Amplify se agregarán cuando existan las aplicaciones
y sus artefactos. No se crea NAT Gateway en esta etapa para evitar su costo
fijo.
