# Correcciones de los hallazgos QA

## Vigencia de exenciones

La liquidación y su previsualización toman el primer día de `period` (`AAAA-MM`)
para evaluar la vigencia de exenciones. Los límites son inclusivos.
No se utiliza la fecha de emisión ni el vencimiento: una liquidación futura no
recibe una exención vencida para ese período, aunque esté vigente hoy.
El mismo criterio se aplica al beneficio social evaluado por el cálculo compartido.
La selección de configuración tributaria no cambia en esta corrección.

QA debe probar períodos anteriores, dentro y posteriores a la vigencia, incluyendo
un vencimiento distinto del mes fiscal. Un mes inválido devuelve HTTP 400.
Las dos pruebas previas de descuentos ahora crean vigencias desde el primer día
del mes para representar explícitamente el período que están validando.

## Idempotencia de pagos

`POST /api/v1/payments` acepta la cabecera opcional `Idempotency-Key`.
El cliente debe generar una clave por intención de pago y reutilizarla en todos
sus reintentos, incluso después de una pérdida de conexión. No debe enviar datos
personales ni secretos como clave.

- Formato: 1–128 caracteres alfanuméricos, punto, guion, guion bajo o dos puntos.
- Alcance: contribuyente + clave. Otra clave representa otro pago.
- Misma clave y contenido: devuelve el mismo pago, sin repetir imputaciones,
  saldo a favor, auditoría ni outbox. Se conserva HTTP 201 del endpoint existente.
- Misma clave con contenido distinto: HTTP 409, `IDEMPOTENCY_KEY_REUSED`.
- Sin cabecera: se conserva el comportamiento previo; no hay deduplicación.
- Se normaliza la escala decimal y se consideran equivalentes las imputaciones
  ausentes y la lista vacía. El orden de imputaciones sí es significativo.
- Una repetición devuelve el estado actual del pago existente, no una copia
  histórica de la respuesta original.

V15 agrega la clave; V17 agrega la huella SHA-256 y la restricción única por contribuyente.
El bloqueo transaccional del contribuyente serializa los registros con clave
también entre instancias. Todo queda en la misma transacción: si falla el pago,
la clave no queda consumida. V1–V16 no se modifican: V17 reemplaza el índice único
global de V15 por uno propio, sin reescribir el archivo histórico ni su checksum.

El frontend genera una clave por intención de pago, la conserva durante los
reintentos y envía `Idempotency-Key`. Esta idempotencia HTTP es local a M5; no
define ni reemplaza contratos outbound de otros módulos.

## Auditoría de solicitudes de exención

Se registran en la misma transacción los cinco pasos:

- `EXEMPTION_REQUESTED`
- `EXEMPTION_REVIEW_STARTED`
- `EXEMPTION_DOCUMENTATION_REQUESTED`
- `EXEMPTION_DOCUMENTATION_SUBMITTED`
- `EXEMPTION_SUBMITTED_FOR_RESOLUTION`

Se preserva la auditoría de aprobación/rechazo existente. Las acciones se vinculan
a `ExemptionRequest` con el identificador de la solicitud.

## Métodos HTTP no permitidos

Una petición autenticada `DELETE /api/v1/liquidations/{id}` recibe HTTP 405 con
`METHOD_NOT_ALLOWED` y cabecera `Allow`. No se agrega un endpoint de borrado.

## Pendiente del PO

Refinanciar un plan `EXPIRED` sigue devolviendo `PLAN_NOT_ACTIVE` (HTTP 422).
La regla no se modifica. El PO debe confirmar si se permiten refinanciaciones
después de la caducidad y, en tal caso, definir sus condiciones.

## Validación

Las regresiones HTTP están en `SecurityTests`; las de pagos concurrentes,
rollback, exenciones y auditoría se ejecutan contra PostgreSQL 17 en
`PostgreSqlIntegrationTest`. Ejecutar desde backend: `mvnw.cmd clean verify`.

El estado consolidado aplica Flyway V1–V17 en DEV/TEST. Producción usa una
migración V18 separada para retirar las tablas DEMO sin modificar los checksums
históricos de V14/V16. Backend, frontend, infraestructura y pruebas de aceptación
forman parte de la corrección y deben validarse juntos en CI.

## Variables de entorno y secretos

`RENTAS_SECURITY_DEV_MODE`, `RENTAS_DEMO_BOOTSTRAP_PASSWORD`,
`TEST_DEMO_USERNAME` y `TEST_DEMO_PASSWORD` forman parte del contrato de DEV/TEST.
Los valores sensibles se obtienen desde Secrets Manager o GitHub Environments;
nunca se versionan. El perfil `prod` prohíbe el modo DEMO.
