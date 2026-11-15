# M5 — niveles de integración y evidencia externa

| Módulo/sistema | Flujo conocido | Evidencia disponible | Nivel máximo probado | Falta para NIVEL 3 | Estado |
|---|---|---|---|---|---|
| M1 Ciudadanos | `citizenUpdated` entrante; salidas que necesitan identidad canónica | Consumidor/inbox/idempotencia y pruebas de contrato; M5 no inventa UUID M1. Outbound puede quedar diferido si falta identidad canónica | N2 contractual | Contrato de identidad/autenticación ratificado, endpoint/routing y prueba conjunta real | BLOCKED_EXTERNAL |
| M2 Atención Ciudadana | `ticketCreated`/`ticketUpdated`; actualización saliente de estado | Inbox, EventLog, orden por `updatedAt`, simulador de desarrollo no productivo y tests de contrato | N2 contractual | M2 real conectado, identidad/UUID de empleado acordado, routing y ACK observable | BLOCKED_EXTERNAL |
| M4 Habilitaciones | tasas/multas entrantes y eventos económicos salientes | Consumidores/pruebas contractuales; IDs M4 originales preservados; datos incompletos no generan efecto parcial | N2 contractual | Confirmación de contrato/fecha de vencimiento y entorno M4 autorizado; routing/ACK | BLOCKED_EXTERNAL |
| M7 Tránsito | `infractionConfirmed`, pagos, planes, reversión y conciliación | Tests de mapping/idempotencia/rollback/concurrencia; prueba PostgreSQL de reinicio de dos procesos para persistencia local/outbox | N2 contractual + recuperación local real | Backend M7/Core real, contrato económico aceptado y recibo/ACK externo verificable | BLOCKED_EXTERNAL |
| M8 Desarrollo Social | Beneficios/exenciones y reglas económicas | Consumidor, inbox, historial, migraciones y pruebas de contrato/locales | N2 contractual | Identidad canónica y contratos/routing/ACK confirmados; `MUNICIPAL_TAX` sigue bloqueado | BLOCKED_EXTERNAL |
| M9 Core | Envelope saliente de ocho campos y Outbox | Persistencia local, guardas, reintentos y estados; `LocalLogEventPublisher` sólo loguea. `PUBLISHED` local no demuestra entrega | N2 contractual/local | Adaptador autorizado, credenciales, routing, reintentos/DLQ y ACK verificable de Core | BLOCKED_EXTERNAL |

## Interpretación

- **N1 — M5 local real:** esta revisión usó frontend Vite, backend Spring Boot y PostgreSQL 17.11 temporal y aislada; Playwright recorrió el portal real para un pago electrónico parcial, reintento idempotente con la misma clave y lectura del saldo actualizado desde TAXPAYER, RENTAS y CASHIER. La suite además validó recuperación local tras reiniciar procesos. No implica conexión ni entrega a otros módulos.
- **N2 — contractual:** pruebas con schemas/envelopes y consumidores/productores o fixtures dentro del repositorio. Son útiles para compatibilidad interna, pero no representan que el módulo externo haya recibido o confirmado el mensaje.
- **N3 — E2E intermodular real:** no se ejecutó. No estaban disponibles/ autorizados en esta ejecución los backends/portales externos ni la entrega ACK de Core. No se habilitó RabbitMQ/Core ni se accedió a otras bases.

No se ejecutó SQL sobre la base compartida `rentas`; las migraciones V1–V28 corrieron sólo en Testcontainers y la base temporal local de esta revisión.
