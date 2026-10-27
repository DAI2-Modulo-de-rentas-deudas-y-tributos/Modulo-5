# Matriz de nombres de eventos M5

La matriz distingue nombres observados en código de nombres mencionados en
documentación de otros equipos. No se renombra ni se duplica ningún evento aquí.

| Productor | Consumidor | Nombre en M5 | Nombre alternativo mencionado | Estado | Acción segura |
|---|---|---|---|---|---|
| M5 | M8 / Core | `overdueDebt` | `debtOverdue` | Diferencia observada; aceptación M8/Core no verificada | Mantener `overdueDebt`; pedir decisión al propietario del contrato antes de cambiarlo |
| M5 | M4/M7/M8 | `paymentRegistered`, `paymentReversed`, `debtSettled` | Nombres compartidos | Propuestas/contratos por confirmar según consumidor | Mantener nombres/campos vigentes; no fabricar copias por módulo |
| M5 | M1/M8 | `exemptionRequested`, `updateExemptionStatus`, `paymentPlanRequested`, `updatePaymentPlanStatus` | Eventos granulares por módulo | Publicación local presente; aceptación externa no verificada | Validar UUID/campos con consumidores y Core |
| M5 | Notificaciones | `notificationSent`, `notificationFailed` | Referencias antiguas en material histórico | No se encontró publisher productivo M5 | No añadir productores/suscripciones desde este módulo |
| M2 → M5 | M5 | `ticketUpdated` | `ticketCreated` | `ticketCreated` no pertenece al flujo M2→M5 documentado para esta versión | Conservar el contrato entrante de `ticketUpdated`; no consumir `ticketCreated` sin confirmación M2 |

## Evidencia y límites

- `ConfirmedIntegrationOutbox.java` publica los eventos económicos compartidos.
- `IntegrationEvents.java`/`EventosDeDominio.java` contienen eventos de dominio
  locales; persistir Outbox no prueba entrega al broker.
- `TicketUpdated` es el evento entrante de M2; el mapa de contratos y sus
  variantes están en `docs/handoff/propuestas_no_ratificadas/`.
- La respuesta de un publisher local no es aceptación de Core. RabbitMQ
  `BROKER_CONFIRMED` tampoco equivale a `CORE_ACCEPTED` ni a `CONSUMER_ACK`.

## Preguntas pendientes

1. M8/Core: ¿`overdueDebt` o `debtOverdue` es el nombre contractual vigente?
2. M4/M7/M8: ¿qué UUID M5, campos opcionales y reglas de filtrado acepta cada
   consumidor para los tres eventos económicos compartidos?
3. Core/M9: ¿cómo se correlaciona identidad del módulo, routing, retries y DLQ?
4. M2: ¿qué versión de esquema y qué variantes concretas de `ticketUpdated`
   debe soportar el consumidor M5?

Hasta recibir respuestas y ejecutar pruebas conjuntas, estos puntos continúan
pendientes externos.
