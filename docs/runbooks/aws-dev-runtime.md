# Runbook: habilitar AWS DEV runtime

## 1. Sincronizar y crear la rama

```powershell
Set-Location C:\Users\prado\Documents\DAI2\Modulo-5
git switch develop
git pull --ff-only origin develop
git switch -c feature/aws-dev-runtime
```

## 2. Validar el cambio

```powershell
terraform fmt -check -recursive infra

terraform -chdir=infra/bootstrap init -backend=false
terraform -chdir=infra/bootstrap validate

terraform -chdir=infra/environments/dev init -backend=false
terraform -chdir=infra/environments/dev validate
```

## 3. Aplicar primero los permisos del bootstrap

El rol OIDC no puede otorgarse permisos nuevos a si mismo. Esta etapa se
ejecuta localmente con el perfil administrador antes del merge:

```powershell
$env:AWS_PROFILE = "rentas-admin"

terraform -chdir=infra/bootstrap init -reconfigure `
  -backend-config="bucket=modulo-5-rentas-tfstate-060712744495-sa-east-1" `
  -backend-config="key=bootstrap/terraform.tfstate" `
  -backend-config="region=sa-east-1" `
  -backend-config="encrypt=true" `
  -backend-config="use_lockfile=true"

terraform -chdir=infra/bootstrap plan -out=bootstrap-runtime.tfplan
terraform -chdir=infra/bootstrap apply bootstrap-runtime.tfplan
```

El plan correcto agrega dos politicas administradas y sus attachments al rol
DEV. Desde el apagado automatico (ADR 0003) esas politicas incluyen
`rds:StartDBInstance`, `rds:StopDBInstance`, los schedules
`modulo-5-rentas-dev-stop-*` y el rol `modulo-5-rentas-dev-nightly-shutdown`:
aplicarlas antes de fusionar, o `cd-dev.yml` y `cd-backend-dev.yml` fallan por
permisos. Si propone reemplazar o eliminar el bucket de estado, el proveedor OIDC o
los roles, no aplicar y revisar la inicializacion del backend.

## 4. Pull Request y despliegue

Publicar la rama y crear un Pull Request hacia `develop`. Luego del merge, el
workflow `Deploy AWS DEV infrastructure` crea los recursos. RDS y CloudFront
pueden tardar varios minutos.

Los outputs `api_base_url` y `frontend_url` se muestran en el resumen del job.
La API respondera temporalmente con error de origen porque el servicio ECS
permanece en cero hasta el primer despliegue real del backend.

## 5. Verificaciones

```powershell
aws ecs describe-services `
  --cluster modulo-5-rentas-dev-cluster `
  --services modulo-5-rentas-dev-backend `
  --profile rentas-admin `
  --region sa-east-1 `
  --query "services[0].[status,desiredCount,runningCount]"

aws rds describe-db-instances `
  --db-instance-identifier modulo-5-rentas-dev-postgres `
  --profile rentas-admin `
  --region sa-east-1 `
  --query "DBInstances[0].[DBInstanceStatus,PubliclyAccessible,EngineVersion]"

aws amplify list-apps `
  --profile rentas-admin `
  --region sa-east-1 `
  --query "apps[?name=='modulo-5-rentas-dev-frontend'].[appId,defaultDomain]"
```

El resultado esperado de ECS antes de recibir codigo es `ACTIVE, 0, 0`; RDS
debe estar `available` y con `PubliclyAccessible` igual a `false`.

## 6. Apagado automatico

DEV solo verifica que el despliegue funciona; para usar la aplicacion esta TEST.
Cada ejecucion de `Deploy backend AWS DEV`:

1. publica la imagen inmutable en ECR (la que luego se promueve a TEST);
2. enciende RDS y espera que este `available`;
3. despliega ECS con una tarea, espera que el servicio se estabilice y exige
   que corra la revision nueva (un rollback del circuit breaker hace fallar
   el run);
4. consulta `/actuator/health` por CloudFront y exige `{"status":"UP"}`;
5. siempre, aun si el deploy fallo, se cancelo o vencio, el job
   `shutdown-dev-runtime` lleva ECS a cero y detiene RDS
   (`scripts/aws/runtime.sh stop`).

Fuera de un despliegue, el estado esperado es ECS `ACTIVE, 0, 0` y RDS
`stopping` o `stopped`. Si quedara encendido, EventBridge Scheduler lo apaga
a las 04:00 (ECS) y 04:15 (RDS) de Argentina; ese respaldo tambien cubre el
reinicio automatico de RDS luego de siete dias detenida.

`Deploy AWS DEV infrastructure` no enciende el runtime. Si un cambio de
Terraform necesita modificar RDS mientras esta detenida, el apply falla con
`InvalidDBInstanceState`. En ese caso encenderla con el perfil administrador,
volver a ejecutar el workflow y luego detenerla:

```powershell
aws rds start-db-instance --db-instance-identifier modulo-5-rentas-dev-postgres --profile rentas-admin --region sa-east-1
aws rds wait db-instance-available --db-instance-identifier modulo-5-rentas-dev-postgres --profile rentas-admin --region sa-east-1
# re-ejecutar "Deploy AWS DEV infrastructure"
aws rds stop-db-instance --db-instance-identifier modulo-5-rentas-dev-postgres --profile rentas-admin --region sa-east-1
```
