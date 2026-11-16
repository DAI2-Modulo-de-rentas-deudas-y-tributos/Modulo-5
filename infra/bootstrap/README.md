# Bootstrap de Terraform

Esta configuracion administra:

- bucket S3 privado, cifrado y versionado para los estados remotos, con server access logs;
- locking nativo de S3 mediante `use_lockfile = true`;
- proveedor OIDC de GitHub Actions;
- roles IAM independientes para DEV, TEST y PROD;
- acceso de cada rol unicamente a su propio estado;
- permisos separados para que cada ambiente administre unicamente sus propios recursos;
- zona Route 53 del dominio propio y certificados ACM del origen HTTPS de cada ALB (`dns.tf`);
- buckets de access logs del bootstrap y de cada ambiente (`access_logs.tf`).

> **Antes de cualquier `apply`, `terraform plan` tiene que mostrar sólo los cambios que se
> buscan.** Este estado se aplica a mano con el perfil administrador; si el código queda atrás
> del estado, un `apply` destruye lo que falta en el código (la zona delegada desde NIC.ar, los
> certificados que usan los ALB, los buckets de logs). Cualquier cambio aplicado tiene que
> quedar commiteado en `develop` el mismo día.

TEST queda autorizado, pero no se crea ningun recurso hasta ejecutar manualmente el workflow con la operacion `apply` y la confirmacion `DEPLOY_TEST`.
No se guardan access keys, claves de PostgreSQL ni tokens personales en GitHub.

## Aplicar cambios del bootstrap

Los permisos nuevos deben aplicarse localmente con el perfil administrador
antes de fusionar la infraestructura de runtime. Desde la raiz del repositorio:

```powershell
$env:AWS_PROFILE = "rentas-admin"
Set-Location infra/bootstrap

terraform init -reconfigure `
  -backend-config="bucket=modulo-5-rentas-tfstate-756663253996-us-east-2" `
  -backend-config="key=bootstrap/terraform.tfstate" `
  -backend-config="region=us-east-2" `
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

## Dominio propio

`modulo-rentas.com.ar` está registrado en NIC Argentina. Route 53 no registra ni transfiere
`.com.ar`, así que el bootstrap crea la zona (`dns.tf`) y NIC.ar le delega el DNS. Es un paso
manual que se hizo una sola vez, después del `apply` que creó la zona:

1. `terraform output route53_name_servers` devuelve los 4 nameservers.
2. En nic.ar (con Clave Fiscal) ir a **Mis dominios → modulo-rentas.com.ar → Delegaciones** y
   cargar esos 4 hosts, sin el punto final.
3. Esperar la propagación (puede tardar horas) y comprobarla:
   `nslookup -type=NS modulo-rentas.com.ar 8.8.8.8` tiene que devolver los `awsdns`.

La zona tiene `prevent_destroy`: recrearla cambia los nameservers y obliga a repetir la
delegación. Los roles de TEST y PROD reciben la política `github-<ambiente>-domain`: TEST sólo
puede escribir registros bajo `test.modulo-rentas.com.ar` y PROD, todos los demás.

## Origen HTTPS de la API

Cada ambiente tiene un certificado ACM `origin-<ambiente>.modulo-rentas.com.ar` en `us-east-2`,
emitido y validado por DNS desde el bootstrap. El módulo `edge` sólo lo busca y publica el
registro A del origen; la política inline `api-origin-tls` limita a cada rol a su propio
`origin-<ambiente>` y le niega explícitamente los de los otros ambientes.

## Access logs

`modulo-5-rentas-<nombre>-access-logs-<cuenta>-us-east-2`, con `<nombre>` en `bootstrap`, `dev`,
`test` y `prod`: cifrados, versionados, sin acceso público, con vencimiento a 30 días y TLS
obligatorio. `bootstrap` recibe los server access logs del bucket de estado
(`terraform-state/`); los de cada ambiente, los del ALB (`alb/`) y los de CloudFront
(`cloudfront/`). Los logs estándar de CloudFront se entregan por ACL, por eso esos tres buckets
usan `BucketOwnerPreferred` y cada rol tiene `cloudfront-access-log-delivery`
(`s3:GetBucketAcl`/`s3:PutBucketAcl` sobre su bucket).
