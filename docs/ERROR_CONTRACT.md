# Contrato estándar de errores API

El backend reutiliza `ApiDtos.ErrorResponse` para las respuestas generadas por `GlobalExceptionHandler` y por Spring Security. Se envía con `Content-Type: application/problem+json` y los miembros RFC 9457 (`type`, `title`, `status`, `detail`, `instance`). Se conservan `code`, `message`, `details`, `traceId`, `timestamp` y `path` para los clientes actuales. OpenAPI publica el esquema común y las respuestas de error.

## Campos

| Campo | Significado |
| --- | --- |
| `code` | Identificador estable para que los clientes clasifiquen el error. |
| `message` | Mensaje funcional, seguro y legible. |
| `details` | Datos estructurados opcionales; en validaciones contiene errores por campo y nunca valores enviados por el usuario. En otros casos puede ser `null`. |
| `traceId` | Correlación de la petición, igual al header `X-Correlation-Id`. Si no se envía un identificador válido, el backend genera uno. |
| `timestamp` | Fecha y hora de generación del error. |
| `status` | Código HTTP, conservado por compatibilidad. |
| `path` | Ruta de la petición, conservada por compatibilidad. |
| `type` | Tipo RFC 9457. Se usa `about:blank` porque M5 no publica una URI normativa por cada código. |
| `title` | Frase HTTP correspondiente al estado. |
| `detail` | Mensaje funcional seguro; coincide con `message`. |
| `instance` | Ruta de la petición; coincide con `path`. |

## Ejemplo: error de validación

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "timestamp": "2026-10-22T14:30:00Z",
  "status": 400,
  "detail": "code: no debe estar vacío",
  "instance": "/api/v1/tax-concepts",
  "code": "VALIDATION_ERROR",
  "message": "code: no debe estar vacío",
  "path": "/api/v1/tax-concepts",
  "traceId": "9e669771-3d87-49a1-bcd7-bf4ebfc164ec",
  "details": {
    "fieldErrors": [
      { "field": "code", "messages": ["no debe estar vacío"] },
      { "field": "name", "messages": ["no debe estar vacío"] }
    ]
  }
}
```

Los mensajes se agrupan por campo y ordenan determinísticamente. No se incluyen valores rechazados ni otros datos del DTO.

## Ejemplo: error de negocio

```json
{
  "type": "about:blank",
  "title": "Not Found",
  "timestamp": "2026-10-22T14:30:00Z",
  "status": 404,
  "detail": "El recurso solicitado no existe",
  "instance": "/api/v1/debts/999999",
  "code": "NOT_FOUND",
  "message": "El recurso solicitado no existe",
  "path": "/api/v1/debts/999999",
  "traceId": "9e669771-3d87-49a1-bcd7-bf4ebfc164ec",
  "details": null
}
```

Los errores inesperados devuelven un mensaje genérico y el `traceId`; el detalle técnico queda únicamente en los logs del servidor. El frontend actual continúa leyendo `message`, `code` y `traceId`, y conserva el objeto completo en `ApiError.details`.

## Ejemplo: autenticación ausente o inválida (401)

Las respuestas 401 generadas por Spring Security o por una sesión DEMO inválida usan el mismo contrato. El identificador de correlación coincide con el header `X-Correlation-Id`.

```json
{
  "type": "about:blank",
  "title": "Unauthorized",
  "timestamp": "2026-10-22T14:30:00Z",
  "status": 401,
  "detail": "Se requiere autenticación",
  "instance": "/api/v1/tax-concepts",
  "code": "UNAUTHENTICATED",
  "message": "Se requiere autenticación",
  "path": "/api/v1/tax-concepts",
  "traceId": "9e669771-3d87-49a1-bcd7-bf4ebfc164ec",
  "details": null
}
```

## Ejemplo: usuario autenticado sin permiso (403)

```json
{
  "type": "about:blank",
  "title": "Forbidden",
  "timestamp": "2026-10-22T14:30:00Z",
  "status": 403,
  "detail": "No tiene permisos para realizar esta operación",
  "instance": "/api/v1/tax-concepts",
  "code": "ACCESS_DENIED",
  "message": "No tiene permisos para realizar esta operación",
  "path": "/api/v1/tax-concepts",
  "traceId": "9e669771-3d87-49a1-bcd7-bf4ebfc164ec",
  "details": null
}
```

Las respuestas de seguridad no incluyen tokens, contraseñas ni detalles de implementación.

## Estados HTTP y ciclo de vida de Deuda

M5 usa `409 Conflict` cuando la operación no puede aplicarse al estado actual del recurso o hay una colisión/concurrencia; `422 Unprocessable Content` cuando el estado/transacción es procesable pero una regla funcional o económica rechaza la solicitud. Los códigos funcionales se mantienen estables. No se introduce una máquina de estados paralela: los estados siguen siendo `PENDING`, `PARTIALLY_PAID`, `PAID` y `CANCELLED`.

| Estado actual | Operación relevante | Resultado vigente | Código / HTTP |
| --- | --- | --- | --- |
| PENDING | Pago, aplicación de crédito, plan elegible, recargo | Permitida si cumple reglas de monto, concepto y pertenencia | 201/200; validación económica 422 |
| PARTIALLY_PAID | Pago, aplicación de crédito, plan elegible, recargo | Permitida si conserva saldo y cumple reglas económicas | 201/200; validación económica 422 |
| PAID | Pago/imputación, crédito, ajuste/reintento incompatible | Rechazada por estado vigente | `DEBT_NOT_PAYABLE` / `DEBT_NOT_ADJUSTABLE` (409) |
| CANCELLED | Pago/imputación, crédito, ajuste/recargo | Rechazada por estado vigente | `DEBT_NOT_PAYABLE`, `DEBT_NOT_ADJUSTABLE` o `DEBT_NOT_CHARGEABLE` (409) |
| Cualquiera incluida en plan activo | Pago directo, crédito o ajuste directo | Rechazada para evitar conflicto con el plan | código específico del dominio (409) |

La emisión crea la deuda en `PENDING`; los pagos y créditos parciales la llevan a `PARTIALLY_PAID`, y al completar el saldo queda `PAID`. No se encontró una operación M5 que cambie la deuda a `CANCELLED`; la auditoría no agrega esa transición. Un intento de pago electrónico sobre una deuda cerrada se registra como `REJECTED` y no crea `Payment` ni `PaymentAllocation`; no es una respuesta de error HTTP.

Los duplicados de creación usan 409 cuando el código existente ya representa conflicto de unicidad; importes inválidos, formato no procesable y elegibilidad económica mantienen 400/422 según su validación. `If-Match` desactualizado usa 412 cuando el endpoint lo implementa; limitación de tasa usa 429 cuando se activa. El error inesperado queda en 500, con detalle técnico únicamente en logs.

El frontend continúa leyendo `message`, `code` y `traceId`; también acepta el media type `application/*+json`, por lo que no requiere adaptación para `application/problem+json`.
