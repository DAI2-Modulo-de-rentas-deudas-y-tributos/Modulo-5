# Bootstrap de Terraform

Esta configuracion administra:

- bucket S3 privado, cifrado y versionado para los estados remotos;
- locking nativo de S3 mediante `use_lockfile = true`;
- proveedor OIDC de GitHub Actions;
- roles IAM independientes para DEV y TEST;
- acceso de cada rol unicamente a su propio estado;
- permisos separados para que DEV y TEST administren unicamente sus propios recursos.

TEST queda autorizado, pero no se crea ningun recurso hasta ejecutar manualmente el workflow con la operacion `apply` y la confirmacion `DEPLOY_TEST`.
No se guardan access keys, claves de PostgreSQL ni tokens personales en GitHub.

## Aplicar cambios del bootstrap

Los permisos nuevos deben aplicarse localmente con el perfil administrador
antes de fusionar la infraestructura de runtime. Desde la raiz del repositorio:

```powershell
$env:AWS_PROFILE = "rentas-admin"
Set-Location infra/bootstrap

terraform init -reconfigure `
  -backend-config="bucket=modulo-5-rentas-tfstate-060712744495-sa-east-1" `
  -backend-config="key=bootstrap/terraform.tfstate" `
  -backend-config="region=sa-east-1" `
  -backend-config="encrypt=true" `
  -backend-config="use_lockfile=true"

terraform fmt -check
terraform validate
terraform plan -out=bootstrap-runtime.tfplan
terraform apply bootstrap-runtime.tfplan
```

Al incorporar TEST, el plan debe agregar una politica inline, dos politicas administradas y dos attachments al rol existente `modulo-5-rentas-github-test-deploy`. Estos cambios IAM no ejecutan aplicaciones ni generan infraestructura TEST. No debe recrear el proveedor OIDC, el bucket de estado ni los roles existentes.

DEV utiliza `environments/dev/terraform.tfstate` y TEST utiliza
`environments/test/terraform.tfstate`.


