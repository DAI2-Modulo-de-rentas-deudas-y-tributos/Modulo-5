# Infraestructura

Infraestructura AWS del modulo, administrada exclusivamente con Terraform.

## Organizacion

- `bootstrap/`: estado remoto, OIDC y permisos de GitHub Actions.
- `modules/network/`: VPC, Internet Gateway y subredes.
- `modules/security/`: aislamiento CloudFront -> ALB -> ECS -> RDS.
- `modules/database/`: PostgreSQL RDS y secreto administrado.
- `modules/edge/`: ALB y endpoint HTTPS CloudFront para la API.
- `modules/ecs/`: ECR, cluster, task definition y servicio Fargate.
- `modules/hosting/`: aplicacion y rama de Amplify Hosting.
- `environments/dev/`: composicion desplegada desde `develop`.
- `environments/test/`: ambiente reservado para promocion posterior.

Cada ambiente utiliza un estado remoto independiente. No se usan Terraform
workspaces para representar ambientes.

## Arquitectura DEV

El frontend se publica en Amplify Hosting. La API se consume por HTTPS desde
CloudFront, que reenvia las solicitudes a un ALB. El ALB es el unico origen
autorizado para alcanzar las tareas Fargate. PostgreSQL permanece en subredes
aisladas y acepta conexiones solamente desde el grupo de seguridad del
backend.

Para evitar el costo fijo de NAT Gateway en DEV, las tareas Fargate usan las
subredes publicas con IP publica. No admiten trafico directo: su grupo de
seguridad solo permite entrada desde el ALB. RDS nunca es publico.

## Flujo de despliegue

1. Un Pull Request ejecuta CI y valida Terraform sin conectarse a AWS.
2. Si cambia IAM, el bootstrap se aplica localmente con `rentas-admin`.
3. Al fusionar infraestructura en `develop`, `cd-dev.yml` ejecuta Terraform.
4. Cuando exista backend, `cd-backend-dev.yml` publica una imagen inmutable en
   ECR y actualiza el servicio ECS.
5. Cuando exista frontend, `cd-frontend-dev.yml` construye `dist` y lo publica
   mediante la API de despliegue manual de Amplify.

Los tres workflows asumen el mismo rol DEV mediante OIDC. No existen access
keys de AWS, tokens personales de GitHub ni claves de base de datos en GitHub.

## Estado inicial sin aplicaciones

El servicio ECS se crea con `desired_count = 0` y una referencia inerte a la
etiqueta `bootstrap`. Por lo tanto, aplicar esta infraestructura no ejecuta
codigo de ejemplo. Los workflows de frontend y backend detectan sus manifiestos
y se omiten hasta que el equipo de desarrollo agregue las aplicaciones.

## Costos

Aunque no haya tareas Fargate ejecutandose, RDS y el Application Load Balancer
generan costo continuo. CloudFront y Amplify se cobran principalmente por uso.
La configuracion DEV usa una instancia RDS pequena, una sola zona y no crea NAT
Gateway. Revisar AWS Cost Explorer y definir un presupuesto antes del apply.
