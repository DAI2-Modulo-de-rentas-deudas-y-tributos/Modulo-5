# Runbook: preparar y promover AWS TEST

## Estado seguro inicial

La configuracion TEST puede fusionarse sin crear recursos. Ningun workflow de
TEST responde a `push`; todos requieren una ejecucion manual. El workflow de
infraestructura usa `plan` como operacion predeterminada.

## Variables del GitHub Environment `test`

Configurar estas variables, sin secretos ni access keys:

- `AWS_REGION`: `us-east-2`
- `AWS_ROLE_ARN`: `arn:aws:iam::756663253996:role/modulo-5-rentas-github-test-deploy`
- `TF_STATE_BUCKET`: `modulo-5-rentas-tfstate-756663253996-us-east-2`
- `TF_STATE_KEY`: `environments/test/terraform.tfstate`
- `TEST_BACKEND_SECRET_ARNS`: mapa JSON con los ARN de Secrets Manager del
  backend, según [Variables y secretos por ambiente](environment-secrets.md).

Agregar un reviewer obligatorio al environment `test` para que toda ejecucion
que use sus credenciales requiera aprobacion humana.

## Aplicar solamente permisos IAM

El rol OIDC no puede otorgarse permisos nuevos a si mismo. Aplicar el bootstrap
localmente con el perfil administrador. Esto crea politicas IAM, pero no crea
VPC, RDS, ALB, ECS, CloudFront ni Amplify TEST.

```powershell
$env:AWS_PROFILE = "rentas-admin"

terraform -chdir=infra/bootstrap init -reconfigure `
  -backend-config="bucket=modulo-5-rentas-tfstate-756663253996-us-east-2" `
  -backend-config="key=bootstrap/terraform.tfstate" `
  -backend-config="region=us-east-2" `
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

Si RDS está detenido, iniciar el runtime antes de promover el backend. Para
aplicar cambios de configuración, revisar primero que el plan de Terraform
conserve el listener 443, los registros de Route 53 y el dominio de Amplify.
El apply registra una revisión base y la promoción copia su configuración a la
imagen candidata.

El workflow copia la misma imagen Docker desde ECR DEV a ECR TEST y actualiza
el servicio TEST con una revision nueva de la task definition.

Frontend:

1. Ejecutar `Promote frontend to AWS TEST`.
2. Indicar como `source_ref` el SHA exacto validado en DEV.
3. Escribir `DEPLOY_TEST` y aprobar el environment.

El frontend se construye desde ese commit usando `VITE_API_BASE_URL` de TEST y
se publica en la rama de deployment manual `test` de Amplify.

## Verificar el borde de TEST

- CloudFront llega por HTTPS al ALB. El ALB agrega la IP de CloudFront al final
  de `X-Forwarded-For` sin incluir el puerto. El backend sólo recibe tráfico del
  security group del ALB.
- Terraform combina las subredes del ALB (`10.60.0.0/24` y `10.60.1.0/24`) con
  la lista administrada `com.amazonaws.global.cloudfront.origin-facing` en
  `RENTAS_SECURITY_TRUSTED_PROXY_CIDRS`. Si AWS actualiza la lista, volver a
  planificar y promover la misma imagen para incorporar los rangos vigentes.
- Tras el arranque, comprobar `/actuator/health` y el preflight
  `OPTIONS /api/v1/dev-auth/login` desde `https://test.modulo-rentas.com.ar` y
  el dominio `amplifyapp.com` de TEST. Ambos deben recibir su propio
  `Access-Control-Allow-Origin`; un origen ajeno debe recibir 403.
- En una ventana de QA, usar dos redes con IP pública distinta y una cuenta
  DEMO de prueba. Agotar el límite por origen desde la primera red con usuarios
  de prueba distintos. Confirmar 429 con `Retry-After` allí y 200 para un login
  válido desde la segunda. En ambas redes usar la API de CloudFront.
- Comprobar HSTS en ambas URLs públicas. En el portal, verificar
  `form-action 'self'`, `font-src 'self'` y la carga de las fuentes locales.
  En la API, comprobar que Swagger y OpenAPI devuelvan 401 o 404 sin sesión y
  que un supervisor DEMO reciba 404 para las simulaciones M2/M4.

## Ventana con Core por AMQPS

1. Acordar con Core la ventana, el hostname TLS del broker, el vhost y las
   credenciales de TEST por el canal de secretos. Coordinar las pruebas antes
   del apagado diario de ECS a las 22:00 de Argentina.
2. Encender TEST desde `Control AWS TEST runtime` con `START_TEST`. Si se
   necesita habilitar el cliente real en la tarea, usar
   `CORE_RABBITMQ_PORT=5671`, `SPRING_RABBITMQ_SSL_ENABLED=true` y las
   referencias a Secrets Manager para usuario y contraseña. Activar publicación
   o consumo sólo para el ensayo acordado.
3. La IP de salida es la Elastic IP del NAT Gateway (ADR 0011): no cambia con
   las promociones ni con el reemplazo de tareas. Se obtiene con
   `terraform output nat_public_ip` en `infra/environments/test`, o con
   `aws ec2 describe-addresses --filters Name=tag:Name,Values=modulo-5-rentas-test-nat-eip`.
   Core la autoriza una vez como `/32` (TCP 5671 para AMQPS y 443 para HTTP).
4. Con Core, verificar la IP observada, el handshake TLS y una conexión AMQP
   autenticada desde la tarea actual antes de publicar o consumir. La regla en
   Core es permanente mientras exista la red de TEST. Al cierre, detener TEST
   con `STOP_TEST` si terminó la ventana.

## Inventario previo al retiro de integraciones (SCRUM-769)

Con una sesión de auditoría autorizada, leer todas las páginas de
`GET /api/v1/integrations/events?direction=INBOUND&size=100` con cada estado
`RECEIVED`, `PENDING`, `FAILED` y `DEAD_LETTER`. Contrastar el nombre y el
envelope con el contrato vigente de cada productor. Registrar fecha de corte,
ambiente, ID externo, tipo, módulo, estado e intentos de los casos legacy o sin
clasificar. Usar los payloads sólo para clasificar y omitirlos del inventario.
