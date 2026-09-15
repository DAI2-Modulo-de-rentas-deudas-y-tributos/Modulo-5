# Modulo ecs

Crea ECR, el cluster ECS, roles IAM, logs, una task definition y el servicio
Fargate conectado al ALB. La definicion inicial referencia la etiqueta
`bootstrap` y el servicio se crea con capacidad cero, por lo que no se ejecuta
codigo de ejemplo ni se generan costos de Fargate antes del primer despliegue.

El pipeline de backend registra revisiones con imagenes identificadas por el
SHA del commit y eleva la capacidad a una tarea. Terraform ignora solamente
`task_definition` y `desired_count` del servicio porque esos dos atributos son
propiedad del pipeline de entrega de la aplicacion.

Por eso la task definition usa `skip_destroy`: cuando Terraform cambia la base
registra una revision nueva y conserva las anteriores, que son las que el pipeline
toma como punto de partida y las que permiten volver atras. Tambien evita pedir
`ecs:DeregisterTaskDefinition`, que no admite permisos por recurso y obligaria a
conceder la accion sobre `*`.

`environment_variables` agrega configuracion no sensible a la tarea.
`secret_variables` recibe un mapa de nombre a ARN de Secrets Manager, amplia la
politica minima del execution role e inyecta cada valor sin exponerlo en Terraform.
Las credenciales RDS administradas se incluyen siempre.

Cuando `spring_profiles_active` incluye `prod`, dos precondiciones de la definicion
de tarea cortan el plan si alguien intenta inyectar `RENTAS_SECURITY_DEV_MODE=true`
o un secreto `RENTAS_DEMO_*`. Son precondiciones y no un bloque `check` porque este
ultimo solo emite una advertencia y dejaria aplicar el despliegue igual.
