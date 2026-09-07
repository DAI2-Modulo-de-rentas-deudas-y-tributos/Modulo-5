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

V15 agrega clave y huella SHA-256 al pago y una restricción única por contribuyente.
El bloqueo transaccional del contribuyente serializa los registros con clave
también entre instancias. Todo queda en la misma transacción: si falla el pago,
la clave no queda consumida. V1–V14 no se modifican.

Frontend no fue modificado: para proteger sus reintentos debe enviar la cabecera
y conservar la clave de esa intención de pago. Esta idempotencia HTTP es local a
M5; no define ni reemplaza contratos outbound de otros módulos.

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

Resultado local del 7 de septiembre de 2026:

- Maven `clean verify`: BUILD SUCCESS.
- 137 pruebas aprobadas; 0 failures, 0 errors, 0 skipped.
- PostgreSQL Testcontainers: 16/16, imagen `postgres:17-alpine`, versión 17.11.
- Base temporal nueva: Flyway V1–V15 aplicadas; historial verificado.
- Hibernate: `ddl-auto=validate` exitoso sobre PostgreSQL.
- JaCoCo: líneas 88,78%; instrucciones 87,16%; ramas 57,73%.
- Gate de líneas >=85%: aprobado.
- No se modificaron frontend, infraestructura ni la base demo local.
- No se realizaron commit, push, merge ni deploy.

## VARIABLES DE ENTORNO / SECRETOS

Sin cambios en variables de entorno ni secretos.
