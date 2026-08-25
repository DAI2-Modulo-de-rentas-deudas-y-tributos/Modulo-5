# Plan de implementación - Módulo 5 Rentas

## Fuentes y prioridad

1. El PDF adjunto define el alcance funcional, endpoints, roles y reglas.
2. Dentro del PDF, las tablas de endpoints y la matriz final prevalecen sobre ejemplos anteriores.
3. La sección "Diseño de backend propuesto (v1)" orienta la implementación, pero sus contratos externos continúan sujetos a acuerdo.
4. El texto de pedido amplía la calidad esperada y el orden de trabajo; no reemplaza contratos explícitos del PDF.

## Estado inicial encontrado

El ZIP era un proyecto Spring Boot vacío: una clase de arranque, un `pom.xml`, Maven Wrapper y un test de contexto. No había dominio, base, API ni integración.

## Implementado en esta entrega

- Infraestructura Spring Boot, PostgreSQL, Flyway, Docker Compose, Dockerfile, Actuator y OpenAPI.
- Seguridad por roles y ownership con adaptador de identidad de desarrollo.
- Error JSON uniforme, auditoría, idempotencia técnica y de negocio, Outbox y publicador local reemplazable.
- Slice funcional: concepto, configuración y aprobación, preview, liquidación, deuda y pago.
- Pago total/parcial/sobrepago, imputación, saldo a favor y reversión total aprobada.
- Flujo `infractionConfirmed` sin endpoint HTTP.
- Plan de pago básico y exención con flujo de aprobación.
- Boletas y PDF, recibos, pago electrónico simulado y aplicación manual de saldo a favor.
- Configuración, simulación, solicitud, excepción, cuotas, caducidad y refinanciación de planes.
- Ajustes con aprobación y corridas masivas con preview, aprobación y ejecución.
- Workflow documental de exenciones y consultas propias.
- Referencias M1, tickets M2, obligaciones M4/M7 y beneficios M8 con handlers idempotentes.
- Indicadores, consultas de auditoría/integración y reproceso desde payload original.
- JaCoCo y matriz completa de contrato en `BACKEND_COMPLETENESS.md`.
- Pruebas automáticas de reglas económicas, eventos, seguridad y ownership.

## Pendiente funcional

- Filtros específicos de cada listado y todos los DTO de respuesta finales.
- `LiquidationComponent` y desglose persistido de descuentos, recargos e intereses.
- Pruebas HTTP directas suficientes para elevar la cobertura total; services ya superan 86 % de líneas.

## Contratos externos pendientes

- `BROKER_PENDING_CONTRACT`: tecnología, tópicos/colas, ACK, DLQ y esquema definitivo.
- JWT/Core: issuer, audience, claims y mapeo final de roles.
- M1: nombres/schema definitivos de eventos de sincronización de contribuyentes.
- M2: `ticketCreated`, `ticketUpdated`, `updateTicketStatus`.
- M4: `permitFeeGenerated`, `commercialFineGenerated`.
- M7: schema final de `infractionConfirmed`, `paymentRegistered`, `debtSettled`.
- M8: `socialBenefitUpdated` y enums definitivos.

## Siguiente orden recomendado

1. Congelar OpenAPI y contratos de eventos con los otros equipos.
2. Completar filtros/DTO de respuesta y `LiquidationComponent`.
3. Incorporar adapter real de broker y JWT del Core.
4. Agregar Testcontainers y pruebas de contrato HTTP/broker.
