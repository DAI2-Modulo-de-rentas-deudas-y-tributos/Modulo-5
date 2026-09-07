# Runbook: preparar y promover AWS TEST

## Estado seguro inicial

La configuracion TEST puede fusionarse sin crear recursos. Ningun workflow de
TEST responde a `push`; todos requieren una ejecucion manual. El workflow de
infraestructura usa `plan` como operacion predeterminada.

## Variables del GitHub Environment `test`

Configurar estas variables, sin secretos ni access keys:

- `AWS_REGION`: `sa-east-1`
- `AWS_ROLE_ARN`: `arn:aws:iam::060712744495:role/modulo-5-rentas-github-test-deploy`
- `TF_STATE_BUCKET`: `modulo-5-rentas-tfstate-060712744495-sa-east-1`
- `TF_STATE_KEY`: `environments/test/terraform.tfstate`

Agregar un reviewer obligatorio al environment `test` para que toda ejecucion
que use sus credenciales requiera aprobacion humana.

## Aplicar solamente permisos IAM

El rol OIDC no puede otorgarse permisos nuevos a si mismo. Aplicar el bootstrap
localmente con el perfil administrador. Esto crea politicas IAM, pero no crea
VPC, RDS, ALB, ECS, CloudFront ni Amplify TEST.

```powershell
$env:AWS_PROFILE = "rentas-admin"

terraform -chdir=infra/bootstrap init -reconfigure `
  -backend-config="bucket=modulo-5-rentas-tfstate-060712744495-sa-east-1" `
  -backend-config="key=bootstrap/terraform.tfstate" `
  -backend-config="region=sa-east-1" `
  -backend-config="encrypt=true" `
  -backend-config="use_lockfile=true"

terraform -chdir=infra/bootstrap validate
terraform -chdir=infra/bootstrap plan "-out=bootstrap-test.tfplan"
terraform -chdir=infra/bootstrap apply bootstrap-test.tfplan
```

El plan esperado agrega una politica inline, dos politicas administradas y dos
attachments al rol TEST. No aplicar si propone eliminar o reemplazar el bucket
de estado, el proveedor OIDC o los roles OIDC.

## Verificar sin desplegar

En GitHub Actions ejecutar `Prepare or deploy AWS TEST infrastructure` con:

- `operation`: `plan`
- `confirmation`: vacio

El job debe terminar con el mensaje `No se aplicaron cambios ni se crearon
recursos`. No seleccionar `apply` durante la preparacion.

## Crear TEST cuando sea necesario

Cuando exista una version candidata y se haya aprobado el costo:

1. Ejecutar `Prepare or deploy AWS TEST infrastructure`.
2. Seleccionar `operation=apply`.
3. Escribir `DEPLOY_TEST` como confirmacion.
4. Aprobar el deployment del GitHub Environment `test`.
5. Esperar a que RDS y el ALB queden disponibles.

La aplicacion se crea inicialmente con cero tareas ECS, por lo que no ejecuta
backend hasta una promocion explicita.

## Promover aplicaciones

Backend:

1. Identificar el tag SHA desplegado y validado en ECR DEV.
2. Ejecutar `Promote backend to AWS TEST` con ese `image_tag`.
3. Escribir `DEPLOY_TEST` y aprobar el environment.

El workflow copia la misma imagen Docker desde ECR DEV a ECR TEST y actualiza
el servicio TEST con una revision nueva de la task definition.

Frontend:

1. Ejecutar `Promote frontend to AWS TEST`.
2. Indicar como `source_ref` el SHA exacto validado en DEV.
3. Escribir `DEPLOY_TEST` y aprobar el environment.

El frontend se construye desde ese commit usando `VITE_API_BASE_URL` de TEST y
se publica en la rama de deployment manual `test` de Amplify.
