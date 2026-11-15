# Reintentos de eventos entrantes en M5

El consumidor de `q.rentas` permanece deshabilitado por defecto (`CORE_RABBITMQ_CONSUMER_ENABLED=false`). La topología local de Compose/Testcontainers no configura ni modifica el broker de Core.

## Modos explícitos

Si se habilita el consumidor, también es obligatorio elegir `CORE_RABBITMQ_RETRY_MODE`:

- `local`: sólo para el broker aislado de M5. M5 publica el mismo mensaje persistente a `rentas.retry`/`q.rentas.retry.1..3` y confirma el original únicamente después de un publisher-confirm positivo y sin retorno de enrutamiento. Un fallo de publicación confirma el original con `NACK(requeue=false)` para no perderlo silenciosamente.
- `core-managed`: no publica a los exchanges/colas locales de M5. Ante una excepción de procesamiento usa `NACK(requeue=false)` y deja el DLX/reintentos al operador de la topología configurada en Core. El consumidor falla al arrancar si no se especifica un modo válido.

En Core, la topología comunicada es `muni.dlx -> q.dlq` y reintentos comunes de 5 s, 30 s, 2 min y 10 min. Esa política debe comprobarse con Core para el vhost/cola y la revisión desplegada antes de habilitar M5. Este repositorio no declara `rentas.retry` ni `q.rentas.retry.*` en el broker compartido.

## ACK, errores durables y recuperación

- Un evento conocido y procesado, duplicado ya deduplicado o ignorado deliberadamente retorna del dispatcher; el listener confirma el ACK después de que los servicios transaccionales terminan.
- `infractionConfirmed` que no puede asociarse aún a un contribuyente o concepto guarda una `ExternalObligation` en `ERROR` y el `IntegrationEventLog` en `FAILED`. Es un estado durable de conciliación, no un fallo transitorio: el listener ACKea y el equipo puede corregir la referencia y usar el flujo explícito de reintento de la obligación. No crea una deuda hasta que los datos necesarios existan.
- Excepciones de contrato o procesamiento que escapan del dispatcher reciben `NACK(requeue=false)`. En modo Core-managed, la clasificación, el límite de intentos y el eventual DLQ corresponden al contrato operativo de Core; el AMQP nack por sí solo no informa si el error fue permanente o transitorio.
- Los errores `FAILED` de M4 todavía se convierten en excepción del dispatcher y por eso siguen la ruta de reintento/NACK. Su semántica durable debe acordarse separadamente antes de habilitar esos tipos.

Core y M5 deben conservar el `eventId` del envelope durante reintentos y dead-lettering. Para correlación operativa, Core debe confirmar que `dead_letters`/`retry_audit` conservan `eventId` y que expone headers de entrega como `x-death` o equivalentes. No se deben usar identificadores de mensajes locales como única evidencia de procesamiento del módulo destino.

El log operativo de rechazo de M5 incluye timestamp del logger, `eventId`, `correlationId`, `eventType`, versión validada, código seguro de `BusinessException` (o clase de excepción) y stack trace sin mensaje. No registra el payload, DNI/CUIT ni credenciales. Los errores durables de conciliación quedan en `integration_event_log`/`external_obligation`, y se consultan por las APIs autorizadas; no se duplica la DLQ de Core.

## Validación local

Las pruebas de RabbitMQ usan un contenedor efímero y topología de prueba aislada. Cubren publisher confirms, mensaje no enrutable, retry local acotado, NACK hacia una DLX local en modo `core-managed` y conservación de `eventId`/headers de dead-letter. No prueban la configuración remota de Core ni su política de reintentos.

## Configuración

- `CORE_RABBITMQ_CONSUMER_ENABLED` queda `false` por defecto.
- `CORE_RABBITMQ_RETRY_MODE=local` sólo corresponde al broker local de Compose.
- `CORE_RABBITMQ_RETRY_MODE=core-managed` requiere validación de Core/DevOps para la cola, DLX, política de retry, DLQ, permisos, TLS y observabilidad antes de cualquier habilitación.
- `CORE_RABBITMQ_RETRY_EXCHANGE`, `CORE_RABBITMQ_RETRY_QUEUE_1..3` y `CORE_RABBITMQ_CONFIRM_TIMEOUT_MS` pertenecen exclusivamente a `local`; no deben apuntar a recursos compartidos de Core.
