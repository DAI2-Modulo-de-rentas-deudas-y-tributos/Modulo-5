# Evidencia E2E local — 27/10/2026

Prueba Playwright 1.64.1 ejecutada en Microsoft Edge sobre Vite, Spring Boot y PostgreSQL 17.11 efímeros; RabbitMQ 4.3 fue local y efímero. El runner creó datos de contribuyentes sintéticos y credenciales aleatorias sólo para el proceso; no se incluyen esas credenciales ni logs.

Resultado: 31 assertions PASS, 0 errores de página/consola/recursos. Se probó el pago parcial, respuesta perdida, reintento con la misma clave de idempotencia, una sola persistencia e imputación, saldo de $750 reflejado en Contribuyente/Caja/Rentas, autorización entre contribuyentes, consulta AUDITOR/SUPERVISOR y publicación `BROKER_CONFIRMED` del Outbox al broker aislado.

La evidencia confirma aceptación del broker local. No prueba entrega a Core, procesamiento externo ni ACK de consumidor. El manifiesto conserva IDs y puertos efímeros de fixtures sintéticos que dejaron de existir al terminar la prueba.

Archivos `01`, `03`–`07`: capturas limpias de los portales. `assertions.json`: comprobaciones por escenario. `run-manifest.json`: versiones y datos sintéticos del entorno efímero. No se publican logs de aplicación.
