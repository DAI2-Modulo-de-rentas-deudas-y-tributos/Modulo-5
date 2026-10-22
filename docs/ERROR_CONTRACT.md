# Contrato estándar de errores API

El backend usa `ApiDtos.ErrorResponse` para las respuestas generadas por `GlobalExceptionHandler`. El esquema se publica como `ErrorResponse` en `/v3/api-docs` y se reutiliza sin cambiar los códigos HTTP ni los códigos funcionales existentes.

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

## Ejemplo: error de validación

```json
{
  "timestamp": "2026-10-22T14:30:00Z",
  "status": 400,
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
  "timestamp": "2026-10-22T14:30:00Z",
  "status": 404,
  "code": "NOT_FOUND",
  "message": "El recurso solicitado no existe",
  "path": "/api/v1/debts/999999",
  "traceId": "9e669771-3d87-49a1-bcd7-bf4ebfc164ec",
  "details": null
}
```

Los errores inesperados devuelven un mensaje genérico y el `traceId`; el detalle técnico queda únicamente en los logs del servidor. El frontend actual continúa leyendo `message`, `code` y `traceId`, y conserva el objeto completo en `ApiError.details`.
