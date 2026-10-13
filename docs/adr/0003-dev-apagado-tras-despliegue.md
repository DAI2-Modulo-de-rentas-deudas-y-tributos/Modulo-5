# ADR 0003: DEV se apaga al terminar cada despliegue

- Estado: aceptado
- Fecha: 2026-10-13

## Contexto

Cada merge a `develop` despliega en AWS DEV y el runtime (una tarea Fargate y
PostgreSQL RDS) quedaba encendido de forma permanente. Nadie usa DEV para
trabajar: el ambiente para probar y mostrar la aplicacion es TEST, que ya tiene
encendido bajo demanda y apagado nocturno. Mantener DEV encendido duplica el
costo de compute sin aportar valor.

## Decision

DEV pasa a ser un ambiente de verificacion de despliegues:

- `cd-backend-dev.yml` enciende RDS, despliega ECS, exige que corra la
  revision nueva, verifica `/actuator/health` por CloudFront y, en un job
  aparte que corre siempre (incluso ante fallo, cancelacion o timeout), lleva
  ECS a cero tareas y detiene RDS.
- La logica de encendido y apagado vive en `scripts/aws/runtime.sh` y la
  comparten DEV y `test-runtime-control.yml`.
- Un apagado nocturno con EventBridge Scheduler (04:00/04:15 de Argentina) es
  el respaldo ante un apagado fallido y ante el reinicio automatico de RDS
  luego de siete dias detenida.
- `cd-dev.yml` (Terraform) no enciende ni apaga el runtime, para no cortar un
  despliegue del backend que corra en paralelo.

## Alternativas

- **Mantener DEV encendido**: costo continuo de Fargate y RDS sin uso.
- **`terraform destroy` al final de cada despliegue**: elimina tambien ALB y
  CloudFront, pero cada despliegue tardaria mucho mas (RDS y CloudFront se
  recrean), las URLs cambiarian y se perderia ECR DEV, de donde TEST promueve
  las imagenes.
- **Solo apagado nocturno**: DEV quedaria encendido hasta la noche despues de
  cada despliegue.

## Consecuencias

- Fuera de un despliegue, DEV no ejecuta tareas y RDS esta detenida. Siguen
  generando costo el ALB, el almacenamiento de RDS, ECR y los logs.
- El frontend DEV sigue publicado en Amplify, pero su API no responde fuera de
  un despliegue: para usar la aplicacion se usa TEST.
- Cada despliegue del backend tarda varios minutos mas por el encendido de RDS.
- Si Terraform necesita modificar RDS mientras esta detenida, el apply falla y
  hay que encenderla manualmente (`docs/runbooks/aws-dev-runtime.md`).
- El rol OIDC de DEV necesita permisos para iniciar y detener RDS y administrar
  los schedules; se aplican desde `infra/bootstrap` antes del merge.
