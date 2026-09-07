# Informe final de implementación - Módulo 5 Rentas

Fecha de verificación: 25 de agosto de 2026.

## Resultado

- Endpoints documentados en matriz: 137.
- Completos localmente en M5: 134.
- Parciales: 0.
- Faltantes locales: 0.
- Bloqueados por Core/JWT: 3 (`login`, `logout`, `me`).
- Implementados fuera de la matriz maestra: 2 (`execute` de corrida, respaldado por el flujo de diseño, y consulta técnica de Outbox).
- OpenAPI: 122 paths y 136 operaciones.

## Cambios de hardening

- filtros, rangos, paginación y sorting mediante Specifications con whitelist;
- DTO explícitos para respuestas y páginas estables;
- persistencia y consulta de componentes monetarios de liquidación;
- locks pesimistas en pagos, imputaciones, planes, reversión y saldo a favor;
- envelope uniforme, idempotencia técnica/de negocio y reproceso original;
- Outbox con `PENDING`, `FAILED`, `PUBLISHED`, `DEAD_LETTER` y metadatos de retry;
- Testcontainers PostgreSQL condicional;
- Actuator y healthchecks de Compose;
- documentación de integración JWT/broker pendiente.

## Evidencia local

| Métrica | Resultado |
|---|---:|
| Tests detectados | 45 |
| Aprobados | 42 |
| Fallos | 0 |
| Errores | 0 |
| Omitidos | 3 Testcontainers por ausencia de Docker |
| Migraciones Flyway | 8 |
| Cobertura de líneas | 64,56 % (470/728) |
| Health | HTTP 200, `UP` |
| Swagger | HTTP 200 |
| OpenAPI | HTTP 200 |

## Pendientes externos o de ambiente

1. **PENDIENTE DE INTEGRACIÓN CORE/JWT:** emisor, claims definitivos, validación y reemplazo del adapter dev.
2. **PENDIENTE DE CONTRATO DE BROKER:** tecnología, tópicos/colas, schemas versionados, ACK, retries y DLQ.
3. **PENDIENTE DE EJECUCIÓN EN ESTA PC:** repetir `PostgreSqlIntegrationTest`, Compose y arranque normal cuando Docker Desktop esté disponible.

No se declara aprobada ninguna de estas tres validaciones pendientes.
