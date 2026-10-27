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
  -backend-config="bucket=modulo-5-rentas-tfstate-756663253996-us-east-2" `
  -backend-config="key=bootstrap/terraform.tfstate" `
  -backend-config="region=us-east-2" `
  -backend-config="encrypt=true" `
  -backend-config="use_lockfile=true"

terraform -chdir=infra/bootstrap plan -out=bootstrap-runtime.tfplan
terraform -chdir=infra/bootstrap apply bootstrap-runtime.tfplan
```

El plan correcto agrega dos politicas administradas y sus attachments al rol
DEV. Si propone reemplazar o eliminar el bucket de estado, el proveedor OIDC o
los roles, no aplicar y revisar la inicializacion del backend.

## 4. Pull Request y despliegue

Publicar la rama y crear un Pull Request hacia `develop`. DEV es **efímero**:
luego del merge, el workflow `Deploy AWS DEV (efímero)` ejecuta en orden:

1. `terraform-apply-dev`: crea la infraestructura. RDS y CloudFront pueden
   tardar varios minutos.
2. `backend` y `frontend`: publican la imagen en ECR y el `dist` en Amplify.
3. `smoke-test-dev`: health del backend a través de CloudFront (incluye la
   conexión a PostgreSQL), frontend servido por Amplify y CORS entre ambos.
4. `terraform-destroy-dev`: destruye todo, aunque una etapa anterior falle o
   se cancele, para que DEV no quede generando costos.

El ciclo completo tarda alrededor de 30 a 40 minutos. Las URLs y el resultado
quedan en el resumen del run. Si se fusionan varios cambios seguidos, GitHub
encola solo el último.

El repositorio ECR `modulo-5-rentas-dev-backend` no se destruye: sale del
estado antes del destroy y el siguiente apply lo reimporta, porque
`cd-backend-test` promueve a TEST las imágenes publicadas ahí.

## 5. Investigar una falla

Como el ambiente ya no existe cuando termina el run, la evidencia está en
GitHub Actions: el resumen del smoke test y, si falla el backend, los eventos
del servicio ECS que imprime `cd-backend-dev`. Para reproducir, volver a
ejecutar `Deploy AWS DEV (efímero)` con `workflow_dispatch` o usar TEST, que es
el ambiente accesible de forma continua.
