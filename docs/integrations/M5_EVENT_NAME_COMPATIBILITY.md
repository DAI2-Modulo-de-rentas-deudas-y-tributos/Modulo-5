# Matriz de nombres de eventos M5

La matriz compara los nombres en código con los contratos recibidos. Para M8,
`EventosConEnvelopeM8V3.pdf` establece el nombre vigente `debtOverdue`.

| Productor | Consumidor | Nombre en M5 | Nombre alternativo mencionado | Estado | Acción segura |
|---|---|---|---|---|---|
| M5 | M8 / Core | `debtOverdue` | `overdueDebt` en V2 y filas locales anteriores | V3 establece `debtOverdue`; entrega por Core no probada | Preparar sólo `debtOverdue`; aislar las filas antiguas `overdueDebt` para revisión, sin enviarlas |
| M5 | M4/M7/M8 | `paymentRegistered`, `paymentReversed`, `debtSettled` | Nombres compartidos | Contratos recibidos y validados localmente; M4 aceptó `citizenId: null` para organizaciones, faltan UUID de origen M4 y prueba E2E | Mantener nombres/campos vigentes; no fabricar copias por módulo |
| M5 | M1/M8 | `exemptionRequested`, `updateExemptionStatus` | Ninguno | M1 informó consumidores locales; M8 aceptó las dos variantes de resolución; entrega real no verificada | Conservar una sola publicación para ambos consumidores y verificar el registro en Core |
| M5 | M1 | `paymentPlanRequested`, `updatePaymentPlanStatus` | Ninguno | M1 informó consumidores locales; entrega real no verificada | Conservar los nombres y verificar el registro en Core |
| M5 | Notificaciones | `notificationSent`, `notificationFailed` | Referencias antiguas en material histórico | No se encontró publisher productivo M5 | No añadir productores/suscripciones desde este módulo |
| M2 → M5 | M5 | `ticketUpdated` | `ticketCreated` | `ticketCreated` no pertenece al flujo M2→M5 documentado para esta versión | Conservar el contrato entrante de `ticketUpdated`; no consumir `ticketCreated` sin confirmación M2 |

## Evidencia y límites

- `ConfirmedIntegrationOutbox.java` publica los eventos económicos compartidos.
- `IntegrationEvents.java`/`EventosDeDominio.java` contienen eventos de dominio
  locales; persistir Outbox no prueba entrega al broker.
- `ticketUpdated` es el evento entrante aprobado de M2 v1.78. Los documentos
  en `docs/handoff/propuestas_no_ratificadas/` son antecedentes, no el contrato
  vigente. El listener M1 ya despacha los cuatro tipos soportados desde el
  envelope Core y conserva correlación y causalidad.
- La respuesta de un publisher local no es aceptación de Core. RabbitMQ
  `BROKER_CONFIRMED` tampoco equivale a `CORE_ACCEPTED` ni a `CONSUMER_ACK`.

## Preguntas pendientes

1. M4: entregar UUID persistentes de las referencias de origen y configurar
   plazos tributarios acordados. M4 aceptó `citizenId: null` para organizaciones;
   M8 aceptó `MUNICIPAL_TAX`; M7 aceptó `TRAFFIC_FINE` con UUID canónico o null.
2. Core/M9: comprobar registro de publicaciones/suscripciones, ruteo, ACK,
   reintentos, DLQ y la versión exigida del UUID `eventId` entrante.
3. M1: desplegar y aportar el vínculo confiable del UUID canónico y el claim
   de identidad final; no inferirlo desde DNI, CUIT ni IDs locales.

Hasta recibir respuestas y ejecutar pruebas conjuntas, estos puntos continúan
pendientes externos.
