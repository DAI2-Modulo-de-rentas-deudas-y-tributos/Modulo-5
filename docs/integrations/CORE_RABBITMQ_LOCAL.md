# Transporte local M5 ↔ Core (sin interoperabilidad real)

M5 conserva `LocalLogEventPublisher` como adaptador predeterminado. Para usar un
RabbitMQ aislado, configurar `BROKER_ADAPTER=rabbitmq` y credenciales Rabbit
explícitas en el proceso/archivo local ignorado. No se usan credenciales
`guest/guest` implícitas; no activar contra Core compartido con este documento.
`CORE_RABBITMQ_HEALTH_ENABLED=true` habilita el indicador Actuator del broker;
por defecto permanece apagado para que el perfil local `local-log` no dependa
de un RabbitMQ que no está ejecutándose.

El publisher envía el JSON persistido por el outbox, sin reconstruir ni ampliar
el envelope, a `muni.inbox`, con mensaje persistente y espera el publisher
confirm. El outbox registra `BROKER_CONFIRMED`: significa aceptación por el
broker, no entrega ni procesamiento por M9/consumidores. `LOCAL_ONLY` indica que
el evento sólo se anotó mediante el adaptador local. Los registros históricos
`PUBLISHED` no se reescriben ni se reclasifican. Las confirmaciones AMQP no
garantizan exactly-once: reintentos conservan el mismo `eventId` para que los
consumidores deduzcan duplicados.
El publicador local todavía admite `PUBLISHED` como antecedente de una cadena
histórica, junto a `LOCAL_ONLY` y `BROKER_CONFIRMED`. Es una compatibilidad
local heredada: ese estado no acredita entrega ni procesamiento por M2. En
modo RabbitMQ sólo `BROKER_CONFIRMED` habilita el evento dependiente.

El consumidor está deshabilitado por defecto. Cuando
`CORE_RABBITMQ_CONSUMER_ENABLED=true`, M5 escucha `q.rentas` sin declararla ni
crear bindings: la cola y el routing pertenecen a Core. Un procesamiento que
retorna sólo después de que el servicio transaccional existente completa su
commit recibe ACK manual; errores generan NACK sin requeue para que la política
de Core/DLX determine el reintento. Este comportamiento se probó sólo en
RabbitMQ 4.3 local, no en la política administrada de Core.

## Dispatch disponible

El dispatcher reusa los contratos y servicios existentes para `citizenUpdated`,
`organizationRegistered`, `representationGranted` y `representationExpired`
(M1), `ticketUpdated` (M2), `permitFeeGenerated` y `commercialFineGenerated` (M4 confirmado),
`infractionConfirmed` (M7) y `socialBenefitUpdated` (M8). Rechaza campos raíz
fuera del conjunto de ocho del envelope; exige `eventId`, `eventType`,
`occurredAt`, `sourceModule` y `data`. `eventVersion` ausente toma el default
`1.0`; `correlationId` y `causationId` pueden omitirse o ser UUID/null.

En modo Rabbit, un evento sucesor espera `BROKER_CONFIRMED` del evento previo;
`LOCAL_ONLY` y el estado histórico ambiguo `PUBLISHED` no habilitan esa cadena.

La entrada M1 desde Core lee `eventId`, `eventType`, `eventVersion`, `occurredAt`
y `sourceModule=ciudadanos` de la raíz, y el objeto de negocio desde `data`.
El envelope original, incluidos `correlationId` y `causationId`, se conserva en
`integration_event_log`; los DTO legados con `producer` y `subject` siguen
aislados de este canal. El typo histórico `occuredAt` no se acepta.
`ticketCreated` no se consume
por M5 según el contrato M2 v1.78 (pertenece al flujo M2→M1). El contrato M4
preliminar permanece bloqueado por `establishmentId → taxpayer`; no se crea
efecto económico. Los resultados de negocio M4/M7 que quedan persistidos como
`FAILED` se NACKean sin requeue y dependen de la política Core/DLX para su
reintento; la prueba local no demuestra que esa política exista en Core.

## API HTTP Core/M9

Core comunicó que `POST /api/v1/events` responde 202 para aceptación, 200 con
`duplicate` para un `eventId` ya ingresado y 422 para un evento inválido.
Deduplica al ingreso, antes del ruteo: reenviar el mismo `eventId` no vuelve a
ejecutar el handler. El reintento por nack y la recuperación mediante
`POST /api/v1/dlq/{id}/retry` pertenecen a Core. Un evento inválido puede
quedar registrado allí. En la entrega Core puede omitir campos null del
envelope y preservar los null contenidos en `data`. Estas reglas están
documentadas como aclaración externa; M5 no tiene un cliente HTTP ni una
confirmación de consumo de M2 para verificarlas extremo a extremo.

Los paths indicados para token de módulo, suscripciones, eventos, dashboard,
DLQ y mapa de tipos quedan pendientes. No se implementa cliente HTTP ni
`POST /api/v1/publications`: faltan OpenAPI/esquemas de request/response,
credenciales/claims JWT y reglas de expiración/renovación. No crear tokens
ficticios ni equiparar una respuesta HTTP con publisher confirm AMQP.

## Pruebas aisladas

`RabbitMqEventPublisherTests` usa un contenedor Testcontainers temporal y crea
exchange/cola de prueba únicamente en ese contenedor (no en configuración de
producción). Demuestra publisher confirm, persistencia/cuerpo idéntico, NACK,
timeout, retorno mandatory sin ruta y recuperación del cliente tras volver a
estar disponible el endpoint del broker de prueba con el mismo envelope.
`IntegrationFlowTests` comprueba reintento del Outbox sin cambiar el payload.
`CoreRabbitMqConsumerTests` verifica dispatch, validación raíz y ACK / NACK sin
requeue del listener. Estas pruebas no validan las suscripciones, autorización
de routing, credenciales, DLX/backoff o recepción por M9 real; tampoco simulan
el reinicio físico del contenedor RabbitMQ.
