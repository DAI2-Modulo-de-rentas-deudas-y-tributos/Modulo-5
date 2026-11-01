# Adaptador HTTP de Outbox hacia Core

El adaptador es opt-in. M5 conserva `local-log` como valor seguro predeterminado; HTTP sólo se selecciona con `BROKER_ADAPTER=core-http`. El adaptador usa el módulo `rentas`, obtiene un token de máquina de `POST /api/v1/auth/module-token` y publica el JSON ya persistido en Outbox, sin reconstruir el envelope ni cambiar `eventId`.

## Configuración externa

| Variable | Uso | Valor predeterminado |
| --- | --- | --- |
| `BROKER_ADAPTER` | Selección del adaptador | `local-log` |
| `CORE_HTTP_BASE_URL` | Origen HTTPS de Core | vacío; obligatorio sólo con `core-http` |
| `CORE_MODULE_SECRET` | Secret de máquina de M5, inyectado en tiempo de ejecución | vacío; obligatorio sólo con `core-http` |
| `CORE_HTTP_CONNECT_TIMEOUT` | Timeout de conexión | `3s` |
| `CORE_HTTP_REQUEST_TIMEOUT` | Timeout por request | `10s` |
| `CORE_HTTP_TOKEN_EXPIRY_SKEW` | Renovación anticipada del token | `PT30S` |
| `CORE_HTTP_ALLOWED_EVENT_TYPES` | Tipos autorizados para publicar | vacío; obligatorio con `core-http` |
| `CORE_HTTP_ALLOWED_EVENT_IDS` | UUID exactos de filas Outbox autorizadas | vacío; obligatorio con `core-http` |

Ambas listas deben estar presentes y ser no vacías para iniciar `core-http`. `CORE_HTTP_ALLOWED_EVENT_IDS` usa el UUID `outbox_event.id`, que para los eventos confirmados coincide con el `eventId` del envelope. Cada consulta de Core HTTP filtra en PostgreSQL por destino, tipo e ID, y procesa una fila por transacción. Una segunda comprobación valida el tipo, destino e igualdad entre ambos IDs antes de enviar. Esto evita publicar el backlog histórico por cambiar el adaptador y permite seleccionar un evento que el scheduler local ya haya marcado `LOCAL_ONLY`, sin tratar ese estado como entrega remota. Las listas son una autorización temporal de ensayo, no una allowlist permanente de producción.

El secret no debe guardarse en archivos versionados, argumentos visibles del proceso ni logs. El cliente sólo admite HTTPS para hosts remotos; HTTP se acepta únicamente para loopback en pruebas locales. No se configura una URL remota por defecto.

## Estados y reintentos

- `LOCAL_ONLY`: el adaptador local no entregó el evento fuera de M5.
- `BROKER_CONFIRMED`: RabbitMQ confirmó que aceptó el mensaje.
- `CORE_ACCEPTED`: Core respondió 202, o 200 duplicado. El OpenAPI 2.0.0 describe el 200 como duplicado, pero no define su cuerpo. El cliente acepta el 200 vacío descrito por OpenAPI; si recibe JSON, exige `duplicate=true` y valida `eventId` cuando viene informado. El cuerpo que devuelve el Core desplegado sigue pendiente de confirmación. Esto acredita aceptación/registro de Core, no entrega ni procesamiento de Ciudadanos M1.
- 422 y 401/403 terminan en `DEAD_LETTER` con código técnico seguro; el payload y el `eventId` original permanecen en el registro de Outbox. Core no define un schema de respuesta 422, así que no se persiste su body no validado, que podría repetir datos del evento.
- Timeout, 408, 429 y 5xx siguen la política existente: hasta cinco intentos y backoff de 5/10/20/40 segundos. Cada intento reutiliza el mismo JSON y `eventId`; el deduplicado de Core cubre una aceptación cuya respuesta se perdió.

El estado terminal requiere intervención operativa antes de reintentar; este cambio no agrega una API de reencolado. Durante una llamada HTTP se mantiene el lock de una sola fila Outbox; `FOR UPDATE SKIP LOCKED` evita que dos workers publiquen simultáneamente la misma fila. Si Core acepta y M5 pierde la transacción antes del commit, la fila conserva su estado anterior y se reintenta con el mismo `eventId`; Core debe contestar como duplicado. La retención del lock queda limitada a una fila y a los timeouts de token/request configurados.

La dependencia solicitud/resolución es deliberadamente estricta por adaptador: `core-http` libera `updateExemptionStatus` o `updatePaymentPlanStatus` sólo si la solicitud correspondiente está `CORE_ACCEPTED`; `local-log` usa sus estados locales y no cuenta `CORE_ACCEPTED` como publicación local. Cambiar a `local-log` mientras la solicitud está `CORE_ACCEPTED` deja la resolución pendiente, sin borrarla ni marcarla como entregada. Si la solicitud estaba `LOCAL_ONLY`, cambiar a `core-http` no habilita la resolución hasta que la solicitud exacta sea aceptada por Core. Al volver a `core-http`, la resolución puede continuar cuando su prerequisito tenga `CORE_ACCEPTED` y su propio ID/tipo se autorice explícitamente. Los cambios de adaptador no reinterpretan estados ni liberan una resolución basándose sólo en `LOCAL_ONLY`.

## Prueba controlada con Core/M1 — lunes 2/11

No ejecutar hasta que Core y M1 habiliten el ambiente, entreguen el secret de máquina por un canal aprobado y M1 proporcione un ciudadano sintético con UUID canónico vinculado oficialmente. `citizenId` no se deriva de un ID numérico, DNI ni CUIT.

1. En un entorno aislado, revisar el catálogo con el token de módulo autorizado mediante `GET /api/v1/event-types/{name}`, `GET /api/v1/publications` y `GET /api/v1/event-types/map`. Confirmar con Core las suscripciones de M1: M5 no tiene permiso para consultarlas ni cambia el catálogo. No registrar publicaciones ni cargar schemas desde M5.
2. Mantener M5 en `local-log` mientras se crea una solicitud sintética real de exención; guardar `requestId`, UUID canónico, `correlationId`, `causationId` y hora desde Outbox. Leer `outbox_event.id` y el `eventId` del envelope persistido en `outbox_event.payload`; comprobar que ambos UUID coincidan. Usar el valor de `outbox_event.id` para `CORE_HTTP_ALLOWED_EVENT_IDS`. Detener M5 antes de que cambie el adaptador.
3. Inyectar temporalmente `BROKER_ADAPTER=core-http`, `CORE_HTTP_BASE_URL`, `CORE_MODULE_SECRET`, los timeouts, `CORE_HTTP_ALLOWED_EVENT_TYPES=exemptionRequested` y `CORE_HTTP_ALLOWED_EVENT_IDS=<UUID exacto de Outbox>` en la configuración protegida del entorno de prueba. Reiniciar M5 y comprobar que la configuración no aparece en logs. Si el scheduler local ya marcó ese evento como `LOCAL_ONLY`, el ID exacto permite seleccionarlo; ningún otro `LOCAL_ONLY` se toma.
4. Esperar `CORE_ACCEPTED` en M5. Registrar el status HTTP. Un 202 sólo significa aceptado/ruteado; un 200 duplicado confirma que Core ya conocía ese `eventId`.
5. Consultar `GET /api/v1/events/{event_id}` con autorización operativa de Core. Guardar el registro, `eventId`, tipo, estado y estado de la entrega a M1. Si hay `correlationId`, consultar también `GET /api/v1/events/journey/{correlation_id}`.
6. Pedir a M1 que confirme en su propia evidencia durable el mismo `eventId`/`requestId`, el UUID canónico y el resultado funcional. Un publisher confirm, la lista de ruteo o la bitácora de Core no sustituyen esta confirmación.
7. Sólo después de validar el primer circuito, continuar en orden con `updateExemptionStatus`, `paymentPlanRequested` y `updatePaymentPlanStatus`; comprobar sus dependencias y usar los mismos registros de Core/M1.
8. Conservar evidencia sanitizada, sin tokens, secretos ni datos personales reales. Para rollback, detener M5 antes de modificar la configuración; eliminar el secret y las dos listas, restablecer `BROKER_ADAPTER=local-log` y reiniciar. No borrar ni editar filas Outbox: `CORE_ACCEPTED` conserva el resultado aceptado por Core y la fila fallida o local puede volver a autorizarse por su ID exacto si se aprueba un nuevo intento. Los eventos fuera del ID autorizado nunca se publicaron por este ensayo.

## Bloqueos y límites

- El OpenAPI de Core 2.0.0 describe HTTP 200 como duplicado, pero no define contenido ni schema para esa respuesta. El cliente acepta el 200 vacío descrito por OpenAPI o un JSON con `duplicate=true` (y valida `eventId` si viene informado); Core debe confirmar el cuerpo efectivo antes de la prueba conjunta. No se verificó esta respuesta contra Core desplegado.
- La API de lectura y permisos para consultar suscripciones deben confirmarse con Core; M1/Core deben aportar evidencia durable de procesamiento.
- La correspondencia entre el `citizenId` Long del evento entrante M1 y el UUID canónico continúa pendiente de un contrato/fuente confiable de M1.
- No se realizó ninguna llamada al Core desplegado, no se registró una publicación, no se ejecutó E2E y no se afirma `CORE_DELIVERED` ni `CONSUMER_ACK`.
