# ADR 0002: Tecnologías de las aplicaciones

- Estado: aceptado
- Fecha: 2026-08-20

## Contexto

El módulo necesita una aplicación web desplegable mediante Amplify Hosting y
un backend transaccional contenerizado que se integre con PostgreSQL.

## Decisión

Usar JavaScript con React y Vite para el frontend web. Usar Java 17 con Spring
Boot para el backend, PostgreSQL como base transaccional y Flyway para versionar
el esquema. Empaquetar únicamente el backend en una imagen Docker multi-stage.

## Consecuencias

- Amplify puede publicar directamente los artefactos estáticos de `dist/`.
- La URL del backend se inyecta durante el build mediante `VITE_API_BASE_URL`.
- El backend conserva un único artefacto para los modos API y worker.
- Las migraciones son repetibles entre desarrollo local, DEV y TEST.
- PostgreSQL local se ejecuta con Docker Compose y en AWS se reemplazará por RDS.
