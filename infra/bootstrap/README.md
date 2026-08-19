# Bootstrap de Terraform

Esta configuración crea una única vez:

- bucket S3 privado, cifrado y versionado para los estados remotos;
- locking nativo de S3 mediante `use_lockfile = true`;
- proveedor OIDC de GitHub Actions;
- roles IAM independientes para DEV y TEST;
- permisos limitados inicialmente al estado Terraform de cada ambiente.

No crea VPC, RDS, ECS, ALB ni otros recursos con costo continuo. Los roles no
reciben todavía permisos para desplegar esos servicios.

## Requisitos

- AWS CLI autenticado con el perfil `rentas-admin`;
- Terraform `>= 1.10`;
- región `sa-east-1`;
- proveedor OIDC, bucket y roles todavía inexistentes.

## Primera aplicación con estado local

Desde PowerShell:

```powershell
$env:AWS_PROFILE = "rentas-admin"
Set-Location infra/bootstrap

terraform init
terraform fmt -check
terraform validate
terraform plan -out bootstrap.tfplan
terraform apply bootstrap.tfplan
```

Revisar el plan antes de confirmar. El resultado esperado es un bucket S3, un
proveedor OIDC, dos roles IAM y sus políticas de acceso al estado.

## Migrar el estado del bootstrap a S3

Después del primer `apply`:

```powershell
$bucket = terraform output -raw terraform_state_bucket
Copy-Item backend.tf.example backend.tf

terraform init -force-copy `
  -backend-config="bucket=$bucket" `
  -backend-config="key=bootstrap/terraform.tfstate" `
  -backend-config="region=sa-east-1" `
  -backend-config="encrypt=true" `
  -backend-config="use_lockfile=true"

terraform state list
```

Agregar `backend.tf` y `.terraform.lock.hcl` al repositorio. Los archivos de
estado y los planes están excluidos por `.gitignore`.

## Backends de los ambientes

DEV utilizará `environments/dev/terraform.tfstate` y TEST utilizará
`environments/test/terraform.tfstate`. Ambos activarán `use_lockfile = true`.

No almacenar access keys ni secretos AWS en GitHub.
