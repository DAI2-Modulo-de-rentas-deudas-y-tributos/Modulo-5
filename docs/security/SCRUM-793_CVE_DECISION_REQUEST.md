# SCRUM-793 — Solicitud de decisión para cuatro CVE residuales

**Estado:** material preparado para revisión; ninguno de los cuatro CVE se declara corregido ni aceptado. No se modificó código ni dependencias como parte de este documento. Fecha de preparación: 2026-10-29.

La evaluación de esta ronda parte de `integration/m5-final-develop-20261028` en `8f8dfc5aaa89a05af59231ca1a55bb5faaa61c60`, más los cambios locales sin commit del gate de cobertura y esta decisión. La última ejecución de `clean verify` terminó correctamente con Temurin 17.0.20.1: 569 tests, 0 fallas, 0 errores y 0 omitidos; JaCoCo global 91,44% líneas (2533/2770) y 66,70% ramas (1743/2613). El gate específico del dominio registró 98,54% líneas (472/479) y 72,71% ramas (634/872) sobre 19 clases de las 14 familias. PostgreSQL Testcontainers 17.11 y migraciones efímeras hasta V31 pasaron. Esta solicitud complementa el escaneo actualizado y no sustituye la decisión formal del responsable de Seguridad.

## Alcance original de SCRUM-793

El ticket original se abrió por `spring-boot-starter-parent:3.5.5` y los hallazgos **CVE-2025-41248** (Spring Security) y **CVE-2025-41249** (Spring Framework). Sus criterios piden actualizar dentro de Spring Boot 3.5.x, verificar que `mvn verify` no cambie comportamiento y ejecutar el escaneo de dependencias para no dejar CVE altas/críticas sin documentar. No exige por sí mismo una migración a Boot 4 ni convierte los cuatro avisos publicados en 2026 en parte de la corrección original.

Las correcciones OSS de 2025 son Spring Security `6.5.5` o superior dentro de la línea afectada y Spring Framework `6.2.11` o superior, respectivamente. La verificación actual resuelve Boot `3.5.16`, Spring Security `6.5.11` y Spring Framework/WebMVC `6.2.19`; por versión, ambos fixes de 2025 están incluidos. El escaneo SCA final histórico dejó documentados los dos findings OSV de WebMVC; no se repitió OSV en esta ronda. Los cuatro CVE 2026 de esta solicitud son riesgos residuales nuevos: no se consideran corregidos por el cambio de parent ni por la ausencia de sus precondiciones en el código.

## Base técnica y clasificación

Las versiones corresponden al árbol Maven documentado para la candidata: Spring Framework/WebMVC `6.2.19`, Spring Data JPA `3.5.13` y Spring AMQP `3.2.12` (compile/runtime, transitivas administradas por Spring Boot `3.5.16`). Las cuatro versiones están dentro de los rangos afectados de sus avisos oficiales. La matriz separa la presencia de una versión vulnerable de evidencia de precondiciones en la aplicación; ausencia de una ruta observada reduce aplicabilidad actual, pero no corrige el componente.

| CVE | Dependencia y severidad del mantenedor | Precondición publicada | Evidencia revisada en M5 | Mitigación observable y riesgo residual | Decisión requerida |
|---|---|---|---|---|---|
| CVE-2026-47890 | `spring-webmvc:6.2.19`; **LOW** según Spring. OSV/GHSA lo etiquetó Critical en el snapshot del scanner, discrepancia que requiere triage; prevalece aquí la severidad del aviso primario, sin borrar la etiqueta SCA. | MVC/WebFlux, envío de fragmentos de vista por SSE y datos controlados por atacante que llegan al stream. | El proyecto usa MVC (`backend/src/main/java/ar/gob/municipalidad/rentas/ApiController.java:11`), pero la búsqueda en `backend/src/main` no encontró `ServerSentEvent`, `SseEmitter`, `text/event-stream`, fragmentos de vista ni `ViewResolver`. Los controladores revisados son APIs REST. Las dos precondiciones funcionales restantes no se observaron. | No se reporta una ruta SSE con vista fragmentada. El artefacto sigue afectado y una funcionalidad SSE futura podría habilitar el escenario. No representa un fix ni un análisis de configuración de todos los entornos desplegados. | Security debe aceptar explícitamente el residual condicionado o exigir actualización compatible del stack antes de integrar/desplegar. Reabrir si se agrega SSE o view rendering. |
| CVE-2026-47884 | `spring-webmvc:6.2.19`; **MEDIUM** según Spring. OSV/GHSA lo etiquetó Critical en el snapshot; solicitar registrar la discrepancia. | `XsltView` en MVC junto con mapping `/**` que renderice una vista sin un nombre explícito. | No se encontraron `XsltView`, `ViewResolver` ni `ModelAndView` en `backend/src/main`; `ApiController.java:11` es `@RequestMapping("/api/v1")` y los controladores inspeccionados son APIs. Los `/**` de CORS/seguridad no son mappings MVC de controladores y no prueban por sí mismos la precondición. | La ruta vulnerable no se encontró en el código inspeccionado, pero WebMVC sigue en rango afectado. Deben revisarse plantillas/proxies/configuración externa si se incorporan server-side views. | Security debe aceptar el residual condicionado o exigir actualización compatible. Reabrir ante XSLT/view resolver o un handler catch-all que renderice vistas. |
| CVE-2026-47834 | `spring-data-jpa:3.5.13`; **MEDIUM** según Spring; versión máxima del rango vulnerable de 3.5.x. | `Sort` no confiable sin sanitizar llega a método de SQL nativo con `Sort`/`Pageable`; DB acepta caracteres no ASCII en sintaxis SQL. | El método nativo localizado es `Repositories.java:127` (`OutboxRepository.findPublishable(Pageable)`). El productor en `IntegrationServices.java:55` invoca con `PageRequest.of(0,50)` constante. La entrada HTTP paginada de `FilteredQueryService.java:76` valida cada propiedad de orden contra `definition.fields`; el servicio usa `repository.findAll(specification,pageable)` (Criteria) en línea 50. No se vio conexión desde HTTP al método nativo del Outbox. | Allowlist y `Pageable` fijo mitigan las rutas de entrada revisadas; no corrigen JPA. Confirmar en revisión que no existan otros llamadores dinámicos fuera del árbol inspeccionado y conservar tests negativos para orden no permitido. | Security/arquitectura decide aceptar el residual acotado temporalmente o planificar actualización de línea Spring Data. No usar el parche 3.5.14 Enterprise sin licencia/disponibilidad. El aviso lista fixes OSS 4.0.7/4.1.1, que implican cambio de línea y validación de compatibilidad. |
| CVE-2026-47860 | Spring AMQP/Spring Rabbit `3.2.12`; **MEDIUM** según Spring; versión máxima del rango vulnerable de 3.2.x. | Atacante puede publicar en cola consumida y la aplicación habilitó descompresión de mensajes; un mensaje comprimido puede agotar heap y terminar/reiniciar el consumidor. | `CoreRabbitMqConsumer.java:96-97` tiene `autoStartup="${rentas.core.amqp.consumer-enabled:false}"`. Búsqueda en `backend/src/main` no encontró `GZipPostProcessor` ni configuración `afterReceivePostProcessors`. No se verificó ACL, permisos de publicación ni configuración efectiva del broker externo. | Consumidor apagado por defecto y sin configuración de descompresión encontrada en el repositorio reducen exposición M5 en la configuración revisada. No demuestran el comportamiento de todos los entornos, adapters o broker ACL. No activar consumo externo mientras no se audite el entorno o se actualice. | Security y DevOps deben confirmar si Rabbit está habilitado y quién puede publicar; aceptar residual sólo con entornos y controles documentados, o actualizar stack. No usar 3.2.13 Enterprise sin soporte legítimo. OSS 4.0.5/4.1.1 requiere evaluar upgrade mayor del framework. |

## Fuentes primarias verificadas

- [Spring Framework CVE-2026-47890](https://spring.io/security/cve-2026-47890/): severidad Low; rango 6.2.0–6.2.19; fix OSS 7.0.9 y 6.2.20 Enterprise Support Only.
- [Spring Framework CVE-2026-47884](https://spring.io/security/cve-2026-47884/): severidad Medium; rango 6.2.0–6.2.19; fix OSS 7.0.9 y 6.2.20 Enterprise Support Only.
- [Spring Data JPA CVE-2026-47834](https://spring.io/security/cve-2026-47834/): severidad Medium; rango 3.5.0–3.5.13; 3.5.14 Enterprise Support Only; 4.0.7/4.1.1 OSS.
- [Spring AMQP CVE-2026-47860](https://spring.io/security/cve-2026-47860/): severidad Medium; rango 3.2.0–3.2.12; 3.2.13 Enterprise Support Only; 4.0.5/4.1.1 OSS.

Las etiquetas OSV del snapshot de 2026-10-28 para los dos advisories WebMVC son más altas que las severidades del proveedor (OSV/GHSA: Critical; Spring: Low/Medium). Esto requiere que el responsable de Seguridad registre qué escala prioriza y por qué; el presente documento conserva ambas etiquetas y no descarta el finding.

## Propuesta de decisión para el responsable de Seguridad

Decidir por CVE, no de forma agregada. Una aceptación temporal debe registrar, como mínimo:

- CVE y artefacto/versión vulnerable resuelta.
- Responsable nominal del riesgo y aprobador de Seguridad.
- Ambientes cubiertos y si la aceptación permite únicamente CI/desarrollo/test; cualquier uso en ambiente compartido o producción necesita aprobación explícita separada.
- Justificación y evidencia del código/configuración que elimina o limita la precondición actual; indicar qué no se ha verificado (por ejemplo, ACL de broker/proxy/entorno).
- Controles compensatorios verificables y owner de cada control.
- Fecha de decisión, fecha de expiración/revisión y hito técnico para corregir/actualizar.
- Condiciones que revocan la aceptación: cambio de ruta, activación de SSE/XSLT, Sort/Pageable dinámico a SQL nativo, activación de Rabbit/descompresión o cambio de ACL.
- Acción requerida antes de abrir Core/RabbitMQ, publicar a un broker compartido o desplegar.

No se propone una aceptación permanente. Si no se aprueba formalmente cada residual, SCRUM-793 queda **PENDIENTE DE DECISIÓN DE SEGURIDAD**. La recomendación técnica es mantener Core/M9 y el consumidor AMQP apagados, sostener la allowlist de orden y planificar una actualización compatible del stack; estas medidas no equivalen a corregir los CVE.

## Alternativas formales para los residuales 2026

### A. Aceptación temporal documentada

Seguridad puede aceptar por CVE el riesgo residual sólo para ambientes expresamente enumerados, con owner nominal, aprobador, fecha de vencimiento/revisión, controles y condiciones de revocación definidos arriba. Para esta candidata, las mitigaciones verificables son: no se observan rutas SSE/XSLT en el código inspeccionado; el `Pageable` del query nativo de Outbox es constante y las ordenaciones HTTP se allowlistean; el consumidor Core/Rabbit está deshabilitado por defecto y no se encontró configuración de descompresión. Deben quedar como evidencia no verificada las ACL del broker, las configuraciones desplegadas fuera del repositorio y cualquier proxy/plantilla externa. Esta opción no permite inferir aprobación para producción, activar Core/Rabbit ni declarar los CVE corregidos.

### B. Migración planificada a Spring Boot 4 OSS corregido

Spring publica las correcciones OSS de estos cuatro avisos en Spring Framework `7.0.9`, Spring Data JPA `4.0.7` y Spring AMQP `4.0.5` (o líneas OSS superiores indicadas por cada aviso). El BOM oficial Spring Boot `4.0.9` administra versiones superiores: Framework `7.0.10`, Data JPA `4.0.8`, AMQP/Rabbit `4.0.6` y Security `7.0.8`. Springdoc declara la línea `3.x` compatible con Boot `4.0.x`; el proyecto actualmente usa Springdoc `2.8.9`, por lo que debe planificarse esa actualización junto al cambio mayor. La ruta alineada es evaluar un BOM de Boot 4; no mezclar versiones manualmente en Boot 3.5 ni aplicar parches Enterprise-only. Antes de autorizarla, el equipo debe validar compatibilidad con Spring Security, Springdoc/OpenAPI, JPA/Hibernate, Rabbit client/AMQP, migraciones y perfiles; luego ejecutar build, Testcontainers, migraciones, pruebas de autorización/contratos y escaneos completos. No se implementó ni se probó esta migración en esta rama.

La alternativa A requiere aprobación escrita del responsable de Seguridad para cada CVE y cada ambiente. La alternativa B requiere autorización de arquitectura/producto para el cambio mayor y coordinación con QA/DevOps. Hasta elegir una alternativa, el estado de SCRUM-793 y de estos hallazgos permanece **ABIERTO / NO CERRADO**.

## Preguntas de aprobación

1. ¿Se acepta de manera temporal y sólo para los ambientes enumerados el residual actual de 47890/47884, considerando las precondiciones de vistas no encontradas y la discrepancia de severidad OSV/Spring?
2. ¿Se acepta el residual de 47834 mientras el `Pageable` nativo siga fijo y las entradas HTTP permanezcan bajo allowlist, o se exige priorizar una migración compatible de Spring Data?
3. ¿Está autorizado Rabbit en algún ambiente de esta candidata? Si sí, ¿quién publica en las colas y qué controles de ACL/descompresión están efectivos? Sin esa evidencia, 47860 no puede cerrarse por análisis de código solamente.
4. ¿Cuál será el plazo/fecha de expiración de cada aceptación y quién es el risk owner?
5. ¿Se bloquea cualquier conexión real con Core/M9 y despliegue compartido hasta completar mitigación/aceptación?

## Variables / secretos

Sin cambios en variables de entorno ni secretos. Este documento no contiene credenciales ni material operativo sensible.
