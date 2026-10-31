# Validación local de V27/V28 — M5 (26/10/2026)

Esta nota registra el estado local de la rama `feature/m5-patterns-security-e2e` en el commit `0d6fb5b`. No contiene datos de respaldo ni credenciales.

## Migración

- Se probó V27/V28 en una base PostgreSQL 17.11 restaurada y aislada antes de aplicarlas a la base local `rentas`.
- Se generó un respaldo nuevo fuera del repositorio; su índice, lectura completa y SHA-256 fueron verificados antes de migrar. El archivo de respaldo **no** se sube a GitHub.
- Flyway aplicó V27 (`payment.integration_id`) y V28 (`payment_plan_request.integration_id` y `payment_plan.integration_id`) a `rentas`. Tras reiniciar el backend, Flyway confirmó V28 sin volver a ejecutar migraciones.
- Los 5 pagos, 2 planes y 1 solicitud existentes conservaron sus claves primarias `Long` y sus datos históricos. Sus UUID de integración son no nulos, únicos y estables tras el reinicio.
- La comparación de huellas de las tres tablas, excluyendo el nuevo campo UUID, coincidió antes y después de migrar. No se asignaron UUID canónicos ficticios a ciudadanos M1.

## Pruebas y ejecución

- Suite completa backend de ese commit: **434 pruebas**, sin fallas, errores ni omisiones, con H2 y PostgreSQL temporal de Testcontainers.
- Suite frontend: **319 pruebas en 35 archivos**. La compilación de producción con Vite también terminó correctamente.
- El backend local construido desde la misma rama respondió `UP` contra `rentas` en V28. La publicación sigue usando el adaptador `local-log`; no se configuró Core ni RabbitMQ.

## Evento histórico

Existe un `debtSettled` histórico para `Debt=1` con identificadores `debt-1` y `liq-1`, marcado `PUBLISHED` por el adaptador local anterior. Ese estado **no acredita entrega a Core**. La fila no fue modificada ni reenviada. Debe mantenerse fuera de cualquier replay automático y conciliarse manualmente cuando exista evidencia de entrega del entorno Core.

Los cinco `paymentRegistered` históricos del outbox pertenecen a `PLATFORM`, no son salidas económicas a Core. No había obligaciones externas M4/M7 ni eventos económicos aplazados en la base local durante esta revisión.

## Pendiente

Core no fue conectado en esta etapa. Por tanto, siguen pendientes la prueba conjunta de entrega y ACK con otros módulos y la vinculación canónica de ciudadanos de M1. Esta rama no modifica el envelope de Core ni crea un PR a `develop`.
