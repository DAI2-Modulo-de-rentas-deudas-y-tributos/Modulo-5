# ADR 0007: Auditoría de tickets en modo de sólo lectura

- **Estado:** Aceptado como criterio funcional de esta implementación.
- **Fecha:** 2026-11-03.
- **Relacionado:** SCRUM-820.

## Contexto

El portal del Auditor ofrece la consulta de tickets derivados a Rentas, pero los endpoints de lectura estaban limitados a RENTAS. El Auditor necesita trazabilidad para su función de control, sin intervenir en el trámite.

## Decisión

Permitir al rol AUDITOR consultar la lista `GET /api/v1/tickets` y el detalle `GET /api/v1/tickets/{id}`. Las operaciones de asignación, actualización, pedido de información, devolución, finalización y rechazo continúan limitadas a RENTAS. No existe un endpoint API de creación de tickets en M5; los tickets ingresan por los consumidores de integración.

## Consecuencias

- La pantalla de tickets del Auditor puede leer tickets sin ampliar sus permisos de escritura.
- Los endpoints de modificación permanecen protegidos y sus intentos denegados se auditan por el mecanismo de seguridad existente.
- Los tests HTTP verifican listado/detalle permitidos y todas las rutas mutables existentes rechazadas con 403, sin alterar estado ni crear actualizaciones.

## Alternativa descartada

Ocultar el menú del Auditor evitaría el 403, pero eliminaría la consulta de trazabilidad que ofrece el portal y no cumple el criterio adoptado para SCRUM-820.
