# Modulo ecs

Crea ECR, el cluster ECS, roles IAM, logs, una task definition y el servicio
Fargate conectado al ALB. La definicion inicial referencia la etiqueta
`bootstrap` y el servicio se crea con capacidad cero, por lo que no se ejecuta
codigo de ejemplo ni se generan costos de Fargate antes del primer despliegue.

El pipeline de backend registra revisiones con imagenes identificadas por el
SHA del commit y eleva la capacidad a una tarea. Terraform ignora solamente
`task_definition` y `desired_count` del servicio porque esos dos atributos son
propiedad del pipeline de entrega de la aplicacion.
