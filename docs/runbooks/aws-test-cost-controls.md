# Controles de costo de AWS TEST

## Objetivo

Mantener TEST disponible bajo demanda, detener automaticamente su compute por la noche y permitir una eliminacion completa solo durante pausas largas.

## Comportamiento configurado

- EventBridge Scheduler reduce ECS a `desired_count = 0` todos los dias a las 22:00 de Argentina.
- EventBridge Scheduler detiene RDS a las 22:15 de Argentina.
- El workflow `Control AWS TEST runtime` permite consultar, encender o detener el runtime.
- El job usa el environment protegido `test`; el encendido requiere aprobacion y la confirmacion `START_TEST`.
- El workflow de infraestructura admite `destroy` con las confirmaciones `DESTROY_TEST` y `DELETE_TEST_DATA`.
- CloudWatch conserva logs durante 7 dias y ECR conserva 5 imagenes.
- AWS Budgets controla un limite mensual predeterminado de USD 25 para costos etiquetados `Environment=test`.

## Prerrequisitos

1. Aplicar `infra/bootstrap` manualmente con el perfil administrador antes de ejecutar el primer plan de TEST.
2. Configurar el environment de GitHub `test` con un revisor requerido.
3. Mantener las variables `AWS_REGION`, `AWS_ROLE_ARN`, `TF_STATE_BUCKET` y `TF_STATE_KEY`.
4. Agregar opcionalmente `BUDGET_ALERT_EMAIL`; no debe guardarse en Git.
5. Luego del primer despliegue, activar la etiqueta de distribucion de costos `Environment` en AWS Billing > Cost allocation tags. AWS puede demorar en mostrarla y procesarla.

Sin `BUDGET_ALERT_EMAIL`, el presupuesto se crea y registra costos, pero no envia alertas. Con el email configurado envia alertas al 50% y 80% del gasto real, y al 100% pronosticado.

## Primer despliegue

1. Ejecutar `Prepare or deploy AWS TEST infrastructure` con `operation=plan`.
2. Revisar que no se destruyan recursos ajenos a TEST.
3. Ejecutarlo nuevamente con `operation=apply` y `confirmation=DEPLOY_TEST`.
4. Promover una imagen de backend validada desde DEV.
5. Promover el frontend desde el mismo commit aprobado.

## Encender TEST

Ejecutar `Control AWS TEST runtime` con:

- `operation=start`
- `confirmation=START_TEST`

El workflow valida que exista una imagen real del backend, inicia RDS, espera que PostgreSQL este disponible y recien entonces lleva ECS a una tarea.

## Detener TEST manualmente

Ejecutar `Control AWS TEST runtime` con:

- `operation=stop`
- `confirmation=STOP_TEST`

El workflow reduce ECS a cero, espera que se estabilice y luego detiene RDS.

## Pausas largas

La destruccion completa elimina RDS sin snapshot final porque TEST usa datos descartables. Antes de continuar, confirmar que migraciones y datos semilla permiten reconstruir el ambiente.

Ejecutar `Prepare or deploy AWS TEST infrastructure` con:

- `operation=destroy`
- `confirmation=DESTROY_TEST`
- `destroy_data_confirmation=DELETE_TEST_DATA`

Esto conserva solamente el bootstrap de IAM y el estado remoto de Terraform. Las URLs generadas por ALB y Amplify cambiaran al recrear TEST.
