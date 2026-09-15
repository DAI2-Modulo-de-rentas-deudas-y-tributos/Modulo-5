# Variables y secretos por ambiente

La configuracion se separa en `local`, `dev` y `test`. Los valores publicos se
versionan como ejemplos o como variables de Terraform; los secretos reales nunca
se guardan en Git, en variables `VITE_*` ni como valores de Terraform.

## Fuentes de configuracion

| Ambiente | Variables no sensibles | Secretos |
| --- | --- | --- |
| Local | `.env`, creado desde `.env.example` | Solo en `.env`, ignorado por Git |
| DEV | `infra/environments/dev` y variables del GitHub Environment `dev` | AWS Secrets Manager |
| TEST | `infra/environments/test` y variables del GitHub Environment `test` | AWS Secrets Manager; `TEST_API_BEARER_TOKEN` opcional en GitHub |

La contraseña de RDS es generada y rotada por RDS. ECS recibe usuario y contraseña
mediante referencias a Secrets Manager; Terraform conserva el ARN, no el valor.
Los secretos adicionales del backend se declaran como un mapa `NOMBRE=ARN` en
`backend_secret_variables`. Cada entrada debe apuntar a un secreto independiente:

```hcl
backend_secret_variables = {
  RENTAS_DEMO_BOOTSTRAP_PASSWORD = "arn:aws:secretsmanager:sa-east-1:000000000000:secret:ejemplo"
}
```

El modulo ECS agrega esos ARN a la politica minima de lectura y los inyecta con la
seccion `secrets` de la definicion de tarea. No copiar el valor del secreto al
archivo `.tfvars`, a GitHub Actions ni a una variable de entorno de Amplify.

## Variables de aplicacion actuales

Los archivos `terraform.tfvars.example` muestran la configuracion completa de cada
ambiente. Hoy DEV y TEST usan el backend real con autenticacion simulada porque el
contrato Core/JWT sigue pendiente:

| Componente | Variable | DEV | TEST |
| --- | --- | --- | --- |
| Backend | `backend_spring_profiles_active` | `dev` | `dev` |
| Backend | `BROKER_ADAPTER` | `local-log` | `local-log` |
| Backend | `OUTBOX_DELAY_MS` | `5000` | `5000` |
| Backend | `RENTAS_SECURITY_DEV_MODE` | `true` | `true` |
| Frontend | `VITE_USE_MOCKS` | `false` | `false` |
| Frontend | `VITE_AUTH_MODE` | `mock` | `mock` |
| Frontend | `VITE_API_BASE_URL` | salida del ALB/CloudFront DEV | salida del ALB/CloudFront TEST |

`VITE_API_BASE_URL` es agregada por Terraform y no se puede reemplazar desde el
mapa libre. Los workflows leen las cuatro variables desde Amplify al construir el
artefacto. Toda variable `VITE_*` queda embebida en JavaScript y debe considerarse
publica.

Cuando Core/JWT este integrado, cambiar el perfil y las variables de autenticacion
en el ambiente correspondiente, crear sus secretos en Secrets Manager y referenciar
solamente los ARN desde Terraform.

## Variables de GitHub Environments

Configurar estos valores en `Settings > Environments`, nunca como secretos porque
no contienen credenciales:

| Variable | DEV | TEST |
| --- | --- | --- |
| `AWS_REGION` | `sa-east-1` | `sa-east-1` |
| `AWS_ROLE_ARN` | rol OIDC de despliegue DEV | rol OIDC de despliegue TEST |
| `TF_STATE_BUCKET` | bucket de estado compartido | bucket de estado compartido |
| `TF_STATE_KEY` | `environments/dev/terraform.tfstate` | `environments/test/terraform.tfstate` |
| `BUDGET_ALERT_EMAIL` | no aplica | opcional |

Los workflows obtienen credenciales AWS temporales mediante OIDC; no crear access
keys de AWS en GitHub. El environment `test` debe conservar reviewer obligatorio.
Mientras TEST use autenticación DEMO, agregar allí los secrets
`TEST_DEMO_USERNAME`, `TEST_DEMO_PASSWORD` y `RENTAS_DEMO_BOOTSTRAP_PASSWORD`.
Este último debe coincidir con el secreto que ECS obtiene desde Secrets Manager.

## Alta y rotacion

1. Crear o rotar el secreto en AWS Secrets Manager dentro de la cuenta y ambiente
   correctos.
2. Agregar o actualizar solamente su ARN en `backend_secret_variables`.
3. Ejecutar `terraform plan` y comprobar que la tarea ECS no exponga el valor.
4. Aplicar Terraform y desplegar una nueva revision del backend.
5. Verificar health check y logs sin imprimir variables sensibles.
