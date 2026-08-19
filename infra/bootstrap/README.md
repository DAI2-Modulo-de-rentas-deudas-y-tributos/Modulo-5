# Bootstrap de Terraform

Esta configuración administra:

- bucket S3 privado, cifrado y versionado para los estados remotos;
- locking nativo de S3 mediante `use_lockfile = true`;
- proveedor OIDC de GitHub Actions;
- roles IAM independientes para DEV y TEST;
- acceso de cada rol únicamente a su propio estado;
- permisos del rol DEV para la red, ECR, ECS Cluster y logs de la fundación.

El rol TEST no recibe todavía permisos para crear infraestructura. Tampoco se
guardan access keys en GitHub.

## Requisitos

- AWS CLI autenticado con el perfil `rentas-admin`;
- Terraform `>= 1.10`;
- región `sa-east-1`;
- variables de los GitHub Environments `dev` y `test` ya configuradas.

## Aplicar cambios del bootstrap

El bootstrap usa el estado remoto `bootstrap/terraform.tfstate`. Los cambios de
permisos se aplican localmente con el usuario administrador y siempre después
de revisar el plan:

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
terraform plan -out=bootstrap.tfplan
terraform apply bootstrap.tfplan
```

El plan de esta etapa debe agregar solamente la política inline
`terraform-dev-foundation` al rol existente de GitHub DEV.

## Backends de los ambientes

DEV utiliza `environments/dev/terraform.tfstate` y TEST utiliza
`environments/test/terraform.tfstate`. Ambos habilitan el locking nativo de S3.
