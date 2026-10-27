# ADR 0005: Adapter para publicar eventos de integración

- Estado: aceptado para los adaptadores locales disponibles
- Fecha: 2026-10-27

## Contexto

Los servicios de integración deben guardar primero la intención en Outbox. La
salida puede ejecutarse en modo local (`local-log`) o mediante RabbitMQ; el
dominio no debe depender de `RabbitTemplate` ni confundir persistencia local
con confirmación del broker.

## Decisión

`EventPublisher` es el puerto que consume el servicio de Outbox. `LocalLogEventPublisher`
y `RabbitMqEventPublisher` son adaptadores para ese puerto: el primero deja el
evento en estado local y el segundo traduce la publicación al cliente AMQP,
espera publisher confirm y considera el retorno obligatorio. El adaptador se
selecciona por `BROKER_ADAPTER`; `local-log` es el valor seguro predeterminado.

No se añade un adaptador HTTP para Core/M9: faltan el OpenAPI vigente,
esquemas de requests/responses, autenticación de módulo, claims y ciclo de
renovación. Crear uno ahora implicaría inventar el contrato.

## Evidencia

- Puerto: `backend/src/main/java/ar/gob/municipalidad/rentas/EventPublisher.java`.
- Adaptador RabbitMQ: `RabbitMqEventPublisher.java`.
- Adaptador local: `IntegrationServices.java` (`LocalLogEventPublisher`).
- Selección/configuración: `application.yml` y `IntegrationServices.java`.
- Pruebas de confirm/return/falla: `RabbitMqEventPublisherTests.java`.

El publicador conserva el envelope persistido; no transforma contratos por
consumidor. `LOCAL_ONLY` sólo confirma la ruta local; `BROKER_CONFIRMED`
confirma el broker; ninguno prueba `CORE_ACCEPTED` ni `CONSUMER_ACK`.

## Alternativas

- Acoplar Outbox al `RabbitTemplate`: descartado porque mezcla el caso de uso
  con transporte.
- Crear cliente HTTP Core ahora: descartado por falta del contrato y de
  credenciales/claims verificables.
- Agregar otra capa de wrappers: descartado; `EventPublisher` ya cumple el
  puerto y los adaptadores existentes implementan el límite de integración.

## Consecuencias

- Se puede probar el contrato localmente con el adaptador temporal RabbitMQ.
- Los estados de entrega mantienen semánticas distintas.
- M9 comunicó cuatro entregas totales (original más reintentos a 5 s, 30 s y
  2 min), con NACK sin requeue. El routing, las suscripciones, la topología/DLQ
  efectiva, TLS y aceptación Core todavía requieren evidencia del entorno M9.
