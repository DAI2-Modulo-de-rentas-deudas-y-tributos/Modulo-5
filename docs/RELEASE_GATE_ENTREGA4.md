# Gate de release — Entrega 4 (17/11/2026)

**Corte de evidencia:** 16/11/2026. **Decisión:** NO-GO para promover a `main`, crear `v2.0-entrega4` o declarar cerrada la E2E.

Este documento resume evidencia verificable; no reemplaza criterios oficiales de la cátedra, aprobación de QA/DevOps ni confirmación de procesamiento de Core/M1. Los “cinco ejes” de abajo son una organización técnica de evidencias, no una rúbrica oficial.

## Referencias y estado de GitHub

| Referencia | SHA | Estado |
|---|---|---|
| `origin/develop` | `769e96f6859ad5fb289547b08cf7a2803afd8243` | CI requerido, estabilidad repetida y promoción automática DEV exitosos; PR #156 incluido. |
| `origin/main` | `139ff7ec9c5d164525832c111cb4191caf93676b` | Distante de develop; conserva commits propios que no deben perderse. |
| Tag `v2.0-entrega4` | — | No encontrado local ni remoto al corte. |

La comparación actual indica **3 commits exclusivos de `main` y 29 exclusivos de `develop`**, con diferencias en **41 rutas** (2.009 adiciones y 719 eliminaciones). Incluye los workflows/diagnósticos del PR #156, además de consumidor AMQP y configuración, Compose/demo, frontend, infraestructura de hosting, documentación, QA y rollback. La comparación requiere revisión archivo por archivo antes de fijar el candidato. No usar una estrategia `ours` para ocultar diferencias.

El [CI #43916987478](https://github.com/DAI2-Modulo-de-rentas-deudas-y-tributos/Modulo-5/actions/runs/43916987478) terminó `success` sobre el SHA exacto `769e96f`; backend, `secret-scan`, frontend, Terraform, Compose, dependency scan, Sonar y `ci-required` pasaron. La [estabilidad #43922481107](https://github.com/DAI2-Modulo-de-rentas-deudas-y-tributos/Modulo-5/actions/runs/43922481107) pasó sus tres corridas y confirmó resultado idéntico. La promoción automática DEV [#43916987627](https://github.com/DAI2-Modulo-de-rentas-deudas-y-tributos/Modulo-5/actions/runs/43916987627) pasó Terraform, despliegues frontend/backend, smoke y destrucción efímera. Son ejecuciones automáticas ya registradas en GitHub, no fueron iniciadas por este trabajo y no prueban interoperabilidad con Core/M1 ni disponibilidad de TEST.

## Matriz G1–G9

No se encontró en el repositorio una fuente oficial con el texto completo de G1–G9. Por eso los estados siguientes describen únicamente evidencia localizada y **no equivalen a aceptar criterios académicos ausentes**.

| Gate | Estado | Evidencia al SHA indicado | Responsable / próximo paso |
|---|---|---|---|
| G1 — arranque reproducible | **PARCIAL** | El job Compose del CI limpio pasó en `769e96f`; README documenta `.env.example`, Compose v2 y `scripts/compose/smoke-test.sh`. No se realizó un ensayo manual cronometrado desde clon limpio en esta tarea. | QA: repetir desde clon limpio con valores locales sintéticos y registrar duración/pasos. |
| G2 | **NO VERIFICABLE** | Criterio oficial no localizado. | PO/cátedra: proporcionar texto y evidencia requerida. |
| G3 | **NO VERIFICABLE** | Criterio oficial no localizado. | PO/cátedra. |
| G4 | **NO VERIFICABLE** | Criterio oficial no localizado; no inferirlo del CI. | PO/cátedra y QA. |
| G5 | **NO VERIFICABLE** | Criterio oficial no localizado. | PO/cátedra. |
| G6 — escaneo de secretos | **CUMPLE EN CI** | `secret-scan` verde para `769e96f`; documento versionado en `docs/security/G6_ESCANEO_SECRETOS.md`. El resultado vale para ese SHA, no sustituye una revisión de historial solicitada por cátedra. | QA: confirmar si se exige artefacto adicional. |
| G7 | **NO VERIFICABLE** | Criterio oficial no localizado. | PO/cátedra. |
| G8 | **NO VERIFICABLE** | Criterio oficial no localizado. | PO/cátedra. |
| G9 | **NO VERIFICABLE** | Criterio oficial no localizado. | PO/cátedra. |

## Cinco ejes de evidencia técnica (no rúbrica oficial)

| Eje | Estado | Evidencia y límite |
|---|---|---|
| Build y regresión | **PASS en develop** | CI #43916987478: backend, frontend, Compose, contratos locales, Terraform validate, SCA, secretos y Sonar jobs terminaron exitosamente; frontend 453 tests según el job. El recuento backend de 2.196 pruebas está verificado en el `clean verify` local de la candidata SCRUM-842 basada en `26f50c8`, no se atribuye aquí al CI de `769e96f`. |
| Cobertura y mutación | **PASS parcial por SHA** | En `26f50c8`: backend global 3.716/3.958 líneas (**93,89%**), frontend 3.349/3.784 (**88,50%**), dominio 493/500 líneas (**98,60%**) y 605/776 ramas (**77,96%**). PIT #43887875817 fue `success` en `e9ddeeb`, con 953 KILLED, 73 NO_COVERAGE y 70 SURVIVED; score publicado **86,95% / 87%**. No es un PIT sobre el candidato de release ni sobre el SHA actual. |
| Seguridad y dependencias | **PARCIAL** | `dependency-scan`, `secret-scan` y Sonar del CI actual pasaron. SCRUM-838 conserva Security Rating C y dos findings atribuidos a Terraform; requiere triage de Nico/Seguridad. ZAP #43909132767 fue `success` en `ed331fa`, sin High/Medium ni FAIL-NEW, con alertas Low/Info; no es sobre SHA actual ni sobre AWS. |
| Rendimiento y estabilidad | **PARCIAL** | k6 #43909132767, en `ed331fa`: 50 usuarios/5 min, p95 de 7,17 ms (boletas), 8,58 ms (deudas), 13,19 ms (simulación), 44.106/44.106 checks y 0/44.109 errores. El JSON resumen marca `thresholds:false` mientras el log CLI imprime thresholds cumplidos; resolver discrepancia del artefacto. Estabilidad #43922481107 pasó 3/3 e informó resultados idénticos sobre `769e96f`; esto no reemplaza k6/ZAP del SHA actual. |
| Integración real y demo | **NO-GO** | El CI prueba Compose/Testcontainers locales. No prueba consumo/publicación contra Core ni procesamiento de M1. Core informó `q.rentas` compartida con backlog; el consumidor no debe habilitarse sin ventana y autorización operativa. SCRUM-837 reporta frontend TEST desactualizado; promoción y smoke de QA pendientes. |

Los artefactos PIT y k6/ZAP se consideran evidencia de sus SHAs indicados solamente. Entre `e9ddeeb` y `26f50c8` no cambió código de producción del dominio mutado, pero el reporte sigue sin ser un run del SHA candidato.

## Riesgos y dependencias abiertas

| Prioridad | Ticket / riesgo | Estado real | Responsable / acción |
|---|---|---|---|
| P0 | Reconciliar `main` y `develop` | Bloqueado: 3 commits de main y diff de 41 rutas aún no revisado integralmente. | Release owner y Nico: decidir PR de promoción que preserve historia y los cambios exclusivos de main. |
| P0 | SCRUM-842 — reproceso de evento fallido | QA reprodujo 500, contador/estado no persistían y no llegaba al quinto intento DEAD_LETTER. La candidata del [Draft PR #170](https://github.com/DAI2-Modulo-de-rentas-deudas-y-tributos/Modulo-5/pull/170), SHA `a5c71a46064a52ea53c86cef591ae5a09f2dba45`, separa la transacción de procesamiento de la transacción que persiste el intento fallido; responde usando el ETag recargado desde PostgreSQL. En JDK 17, `clean verify` local: 2.196 pruebas, 0 fallos, 0 errores, 1 omitida; JaCoCo y `DomainCoverageGate` aprobaron; también pasó el caso HTTP contra PostgreSQL 17.11 y el caso focal de 52 pruebas. En CI #43932171531 backend, frontend, Compose, dependency scan, secret scan, Terraform y `ci-required` pasaron; el análisis Sonar seguía en curso al corte. QA aún debe retestar. | Santiago: esperar el cierre del análisis; QA: retestar el flujo y el límite de 5 en el SHA del PR. |
| P0 | Tag / aprobación del SHA | Tag ausente; no hay autorización para crear tag ni desplegar. | PO/cátedra y release owner: aprobar SHA exacto después de CI, QA y reconciliación. |
| P1 | SCRUM-837 — demo en AWS TEST | La promoción automática DEV #43916987627 pasó el 16/11; no prueba que AWS TEST esté actualizado ni que el frontend TEST esté disponible. | Nico: confirmar promoción TEST frontend/backend, URL y ventana. QA: smoke end-to-end al tener ventana. |
| P1 | SCRUM-832 — proxy confiable | Compose pasó con CIDR configurado; CIDR efectivo de ALB/CloudFront de TEST no está verificado aquí. | Nico: entregar CIDR y cadena de proxies aprobados; QA: repetir escenarios en TEST. |
| P1 | SCRUM-838 — Security Rating C | Sonar CI pasó; Jira reporta dos findings de Terraform y rating C. | Nico/Seguridad: revisar findings en consola y decidir corrección/aceptación. No tocar Terraform en esta tarea. |
| P1 | SCRUM-706 — sesiones | Ticket figura Finalizada, pero código observado usa `sessionStorage`/`X-Demo-Session`, no sesión de navegador `HttpOnly` emitida por backend. | Responsable del ticket/PO: cotejar criterios y corregir estado o acordar alcance; no se simula una cookie desde React. |
| P1 | SCRUM-836 — anulación | API/flujo no implementado; PO indicó diferir, falta aceptación escrita de cátedra si la HU forma parte de la entrega. | PO/cátedra: confirmar diferimiento. |
| P1 | SCRUM-652 / SCRUM-745 | E2E externo y ambiente integrado siguen pendientes; no se consultaron servicios ni colas compartidas. | Core/M1/M2 + Nico + QA: acordar entorno/ventana y evidencias. |
| P2 | Anexo IA y Teams | Anexo tiene corte 27/10; no hay evidencia versionada de exportación integral ni publicación en Teams. No se encontró una feature IA/LLM verificable integrada en M2/M5 ni criterios de Clase 14/G1–G9 en el repositorio. | PO/cátedra: enviar consigna oficial, confirmar si se exige IA y formato del anexo; equipo: aportar evidencia real de Teams. |

## Secuencia segura para la release

1. Obtener de PO/cátedra los criterios G1–G9 y confirmar qué pruebas deben estar sobre el mismo SHA del candidato.
2. Revisar los 3 commits exclusivos de `main`, los 29 de `develop` y las 41 rutas de diff, especialmente workflows, `infra/**`, migraciones, configuración AMQP y datos demo. No aplicar `ours` ni descartar commits sin revisión explícita.
3. Resolver SCRUM-842 y ejecutar QA del endpoint de reproceso en el SHA final. Mantener la corrección como PR Draft hasta tener `clean verify` y CI verde.
4. Nico y QA coordinan la promoción frontend/backend a TEST, disponibilidad, configuración de proxy y smoke funcional. No activar `q.rentas` con backlog mixto; cualquier E2E Core exige ventana autorizada separada y prueba de procesamiento por el consumidor, no sólo ACK.
5. Crear un PR de promoción hacia `main` sólo después de reconciliar el diff. Verificar el `ci-required` y todos los checks sobre el HEAD exacto del PR. Revisar el workflow `cd-prod.yml`: un push/merge en `main` puede iniciar automáticamente CI, Terraform apply, Flyway y despliegue PROD. La revisión/aprobación de Nico y la ventana de release son obligatorias operativamente aunque las reglas del branch no pidan aprobación humana.
6. Antes de promover, acordar rollback con Nico: conservar SHA anterior, tarea/artefacto anterior, estado de migración y procedimiento aprobado. V32 amplía `due_date_processing` con estado/campos, constraints e índice; no hay rollback de esquema documentado aquí. No asumir que revertir el binario revierte Flyway.
7. Tras el merge autorizado, registrar SHA exacto de `main`, confirmar CI y el resultado de CD/health checks; verificar smoke de QA y autorización para producción.
8. Sólo con aprobación explícita del SHA final crear/publicar `v2.0-entrega4`. Los workflows PIT, estabilidad y no funcionales escuchan tags `v*`; el `ci.yml` ordinario escucha `main`/`develop`, no tags. Verificar los runs disparados por el tag y guardar enlaces/artifacts. Un tag no equivale a deploy ni a prueba E2E.
9. Entregar changelog, matriz de trazabilidad `HU → criterio → test → resultado → defecto`, manual/PDF y presentación; comprobar acceso/publicación en Teams antes de afirmarlo.

## Evidencia que debe quedar en la aprobación

- SHA aprobado de `develop`, SHA resultante de `main` y diff de promoción revisado.
- URL del PR de release y de los checks requeridos sobre el HEAD exacto.
- Resultado QA de SCRUM-822/834 y de la regresión SCRUM-842; defectos abiertos con severidad honesta.
- Confirmación DevOps de ventana, rollback, migraciones y despliegue automático.
- Run IDs de PIT, estabilidad, k6/ZAP asociados al SHA/tag aceptado, o decisión escrita sobre evidencia de SHA anterior.
- Tag exacto y sus workflows; evidencia de demo/Teams sólo si realmente se publicó.
- Aprobación formal de PO/cátedra y evidencia de IA únicamente según consigna oficial.

**No ejecutar desde este checklist:** merge a `main`, creación de tag, despliegue, `terraform apply/destroy`, activación de consumidores, publicaciones o reprocesos reales.
