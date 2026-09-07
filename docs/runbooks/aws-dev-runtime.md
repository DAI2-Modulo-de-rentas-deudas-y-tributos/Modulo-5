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
DEV. Si propone reemplazar o eliminar el bucket de estado, el proveedor OIDC o
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
