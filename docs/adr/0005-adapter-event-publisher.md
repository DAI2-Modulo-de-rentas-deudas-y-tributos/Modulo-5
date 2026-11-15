# ADR 0005: Adapter para publicar eventos de integración

- Estado: HTTP opt-in preparado; activación operativa pendiente
- Fecha: 2026-10-31

## Contexto

Los servicios de integración deben guardar primero la intención en Outbox. La
salida puede ejecutarse en modo local (`local-log`) o mediante RabbitMQ; el
dominio no debe depender de `RabbitTemplate` ni confundir persistencia local
con confirmación del broker.

## Decisión

`EventPublisher` es el puerto que consume el servicio de Outbox. `LocalLogEventPublisher`,
`RabbitMqEventPublisher` y el adaptador HTTP opt-in son adaptadores para ese puerto:
el primero deja el evento en estado local, RabbitMQ espera publisher confirm y el
retorno obligatorio, y HTTP solicita un token de módulo y publica el envelope ya
persistido en Core. El selector sigue siendo `BROKER_ADAPTER`; `local-log` es el
valor seguro predeterminado.

El adaptador HTTP sigue el OpenAPI Core 2.0.0 proporcionado para esta integración.
HTTP 202 y 200 con `duplicate=true` quedan como `CORE_ACCEPTED`, distinto de
`BROKER_CONFIRMED`; ninguno demuestra procesamiento por M1. El adaptador permanece
desactivado por defecto y aún requiere prueba operativa conjunta con Core/M1.

## Evidencia

- Puerto: `backend/src/main/java/ar/gob/municipalidad/rentas/EventPublisher.java`.
- Adaptador RabbitMQ: `RabbitMqEventPublisher.java`.
- Adaptador HTTP Core: `CoreHttpEventPublisher.java` (opt-in; tests con servidor loopback).
- Adaptador local: `IntegrationServices.java` (`LocalLogEventPublisher`).
- Selección/configuración: `application.yml` y `IntegrationServices.java`.
- Pruebas de confirm/return/falla: `RabbitMqEventPublisherTests.java`.

El publicador conserva el envelope persistido; no transforma contratos por
consumidor. `LOCAL_ONLY` sólo confirma la ruta local; `BROKER_CONFIRMED`
confirma el broker; `CORE_ACCEPTED` acredita aceptación de Core. Ninguno prueba
`CONSUMER_ACK`.

## Alternativas

- Acoplar Outbox al `RabbitTemplate`: descartado porque mezcla el caso de uso
  con transporte.
- Activar HTTP por defecto: descartado porque la operación depende de credenciales,
  permisos y coordinación de entrega que deben habilitarse explícitamente.
- Agregar otra capa de wrappers: descartado; `EventPublisher` ya cumple el
  puerto y los adaptadores existentes implementan el límite de integración.

## Consecuencias

- Se puede probar el contrato localmente con el adaptador temporal RabbitMQ.
- Los estados de entrega mantienen semánticas distintas.
- M9 comunicó cuatro entregas totales (original más reintentos a 5 s, 30 s y
  2 min), con NACK sin requeue. El routing, las suscripciones, la topología/DLQ
  efectiva, TLS y aceptación Core todavía requieren evidencia del entorno M9.
