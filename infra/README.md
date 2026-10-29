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
- `environments/test/`: composicion aislada preparada para promocion manual.
- `environments/prod/`: composicion persistente desplegada desde `main`.

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
3. Al fusionar en `develop`, `cd-dev.yml` levanta un DEV **efímero**: aplica
   Terraform, despliega backend (`cd-backend.yml`, imagen inmutable en ECR)
   y frontend (`cd-frontend.yml`, `dist` en Amplify), corre un smoke test y
   destruye el ambiente, falle o no la verificación.
4. Solo sobrevive el repositorio ECR de DEV, porque TEST promueve las imágenes
   desde ahí.
5. Cada merge o commit a `main` dispara `cd-prod.yml`, que actualiza PROD.

Los workflows DEV asumen el rol DEV, las promociones manuales el rol TEST y `cd-prod.yml` el rol PROD mediante OIDC. No existen access keys de AWS, tokens personales de GitHub ni claves de base de datos en GitHub.

Las variables no sensibles de frontend y backend se definen por ambiente en
`environments/{dev,test,prod}`. Los secretos del backend se referencian por ARN y ECS
los obtiene desde Secrets Manager al iniciar la tarea. La matriz y el procedimiento
de alta o rotacion estan en `docs/runbooks/environment-secrets.md`.

## Promocion a TEST

TEST no se despliega por pushes ni merges. El workflow `cd-test.yml` ejecuta `plan` por defecto y solo aplica con la seleccion explicita `apply` mas la confirmacion `DEPLOY_TEST`. El backend reutiliza una imagen inmutable publicada previamente en ECR DEV. El frontend se reconstruye desde el mismo commit porque necesita la URL de API propia de TEST.

## PROD desde `main`

PROD es persistente y tiene su propia base PostgreSQL, con proteccion contra
eliminacion, 7 dias de backups y snapshot final. `cd-prod.yml` corre el CI, aplica
Terraform y despliega backend y frontend en cada push a `main`. Un deploy en curso
nunca se cancela; los commits que llegan mientras tanto esperan y solo se despliega
el mas reciente. Decision completa en `docs/adr/0003-ambiente-produccion.md`.

Alta, una sola vez:

1. Aplicar `bootstrap/` localmente con `rentas-admin` (`terraform plan` y luego
   `apply`): crea el rol PROD y la denegacion de DEV/TEST sobre recursos
   `Environment=prod`.
2. En GitHub, crear el Environment `prod` con *Deployment branches* limitado a
   `main`. Sin esa regla cualquier rama podria asumir el rol PROD.
3. Cargar sus variables: `AWS_REGION`, `AWS_ROLE_ARN` (salida
   `github_prod_role_arn`), `TF_STATE_BUCKET` y
   `TF_STATE_KEY=environments/prod/terraform.tfstate`.
4. Ejecutar `cd-prod.yml` manualmente o fusionar en `main`.

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

