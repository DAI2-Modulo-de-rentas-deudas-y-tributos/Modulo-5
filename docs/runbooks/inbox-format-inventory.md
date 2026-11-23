# Inventario de formatos en el Inbox de Rentas

SCRUM-769. Ejecutar el SQL únicamente mediante una conexión PostgreSQL 16 o posterior autorizada para lectura. La consulta agrega por estado y clasificación; no devuelve IDs, errores crudos ni payloads. No es una validación de esquemas, un recuento del broker ni una autorización de reproceso.

```sh
psql -X -v ON_ERROR_STOP=1 -f scripts/test/inventory-inbox-formats.sql
```

Proveer la conexión por el mecanismo aprobado del operador. La transacción es de solo lectura y limita la consulta a 15 segundos.

- `LEGACY_TYPE` y `LEGACY_ROUTE` indican los fallbacks que aún usa `IntegrationReprocessService`.
- `CURRENT_ROUTE` señala la ruta que elegiría ese servicio. No garantiza que el evento cumpla su contrato.
- `INVALID_ENVELOPE` y `UNCLASSIFIED` requieren revisión. Un payload vacío no se interpreta como historial descartable.
- Una salida vacía significa que la consulta no encontró eventos entrantes en los cuatro estados inspeccionados. No dice nada sobre mensajes listos, sin ACK, retries o DLQ administrados por Core.

Para SCRUM-771, SCRUM-772 y SCRUM-784, conservar consumidores, fallbacks y rutas históricas hasta revisar tanto el agregado autorizado del Inbox como el inventario de Core y las dependencias de reproceso. El puerto `EventPublisher` (SCRUM-775) sigue siendo requerido por los transportes vigentes.
