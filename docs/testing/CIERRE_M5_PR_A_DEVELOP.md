# Módulo 5 Rentas — cierre de integración hacia `develop`

**Actualizado:** 29/10/2026
**Estado:** PR #78 abierto en Draft. En el SHA `2eb704dd` Maven y JaCoCo pasaron; el job backend terminó en FAILURE por la aceptación HTTP operativa de configuración tributaria, bloqueada por la regla de autoaprobación. No listo para merge.

## Estado de Git y publicación

- Repositorio: `DAI2-Modulo-de-rentas-deudas-y-tributos/Modulo-5`.
- Rama candidata: `integration/m5-final-develop-20261028`.
- Base remota: `develop` en `27e6f6d11181da870dc3dcb4c6edc14da5155100`.
- HEAD publicado al momento de la actualización de este documento: `c4e9be33de68ec9d5331ec72c548f4ec468f7e89`. Este informe se incorporará mediante un commit documental posterior, por lo que el HEAD avanzará.
- PR: [#78 — chore(m5): consolidar integración y calidad antes de develop](https://github.com/DAI2-Modulo-de-rentas-deudas-y-tributos/Modulo-5/pull/78), base `develop`, compare `integration/m5-final-develop-20261028`.
- Estado observado: OPEN, DRAFT, MERGEABLE. No se hizo merge ni deploy.
- Último run revisado: HEAD `2eb704dd7e3fcbad9ba1bfc907151ec0e7842b8f`; `application-detection`, `repository-structure`, `terraform` y `frontend` SUCCESS. Maven/JaCoCo del job backend SUCCESS; el paso de aceptación PDF/bulk recibió 403 `SELF_APPROVAL_FORBIDDEN`; `backend` y `ci-required` quedaron FAILURE.
- El diff antes de agregar este informe final era de 57 commits y 302 archivos. Los PR anteriores #70, #71, #73 y #75 siguen en Draft; #74 y #76 figuran mergeados. Ninguno fue modificado en esta etapa.

## Alcance de la candidata

La candidata consolida cambios acumulados de backend, frontend, contratos, pruebas, documentación y DevOps de varias etapas y responsables. No debe atribuirse todo el contenido a un único autor ni fusionarse sin revisión de los propietarios correspondientes. Se identificaron tres commits de Rodrigo Blanco/RodriBlan en la ascendencia. También se requiere revisión DevOps de la modificación de `.github/workflows/ci.yml`, que añade el reporte JaCoCo al artefacto.

La integración sigue preparada para Core/M9, pero RabbitMQ local no demuestra entrega al Core externo ni `ACK` de su consumidor. No se conectó Core/RabbitMQ compartido.

## Evidencia de validación

| Verificación | Evidencia | Alcance y límite |
|---|---|---|
| Backend Maven | Reportes locales del 28/10: 568 tests, 0 fallos, 0 errores, 0 omitidos; Temurin 17.0.20.1 | Histórica; no repetida localmente sobre el HEAD remoto. CI del PR #78 es la verificación actual. |
| PostgreSQL/Flyway | Reporte local: PostgreSQL Testcontainers 17.11, Flyway hasta V31 | Histórica; no se ejecutaron migraciones sobre la base compartida `rentas`. |
| JaCoCo | 91,80% líneas; 89,47% instrucciones; 66,70% ramas | XML histórico de la ejecución del 28/10. |
| PIT de `PaymentPlanService` | Dos sondas: 11 KILLED, 1 NO_COVERAGE, 0 MEMORY_ERROR y 0 TIMED_OUT | Evidencia parcial. No equivale al gate global. |
| PIT global / SCRUM-807 | No hay reporte global completo ni mutation score verificable | NO VERIFICABLE; umbral exigido >=60% sobre las 14 clases y descubrimiento íntegro de tests. |
| SCRUM-793 / SCA | Se aplicaron overrides compatibles en dependencias no cubiertas por los cuatro CVE Spring; solicitud de decisión documentada | Cuatro CVE siguen pendientes de corrección compatible o aceptación formal por Seguridad. |
| Tests del generador PIT | 4/4 PASS | Ejecutados localmente en este SHA. |
| Workflow PIT | YAML parseado con SnakeYAML | No se ejecutaron `actionlint` ni el workflow remoto; revisión DevOps/Security pendiente. |
| `git diff --check` | PASS antes de la publicación inicial | Repetir tras el commit documental final. |

La estación no pudo ejecutar el backend completo con las condiciones contractuales: no hay JDK 17 en la ruta disponible, Docker Desktop no responde y la memoria libre observada fue de 2,12 GiB. `ExemptionRequestValidationTests` sí pasó de forma aislada con Temurin 21.0.12.1: 17/17. El run remoto del SHA `2eb704dd` ejecutó `clean verify` en Temurin 17 y reportó 568/568, 0 fallos/errores/omitidos y gate JaCoCo aprobado. La validación HTTP final del mismo job no pasó, por lo que el job global sigue rojo.

## Diagnóstico del CI en los dos SHA publicados

En el run de `c4e9be33`, `clean verify` registró 568 tests, 16 fallos y 1 error. Los fallos eran conteos absolutos de solicitudes de exención en `ExemptionRequestValidationTests`, que asumían una tabla vacía, y el error usaba `java.exe` fijo en Ubuntu. Se corrigió comparando el delta contra el estado inicial de cada test y eligiendo el binario Java según el SO. La clase focal pasó 17/17 en Temurin 21 local.

En el nuevo run del SHA `2eb704dd7e3fcbad9ba1bfc907151ec0e7842b8f`, el backend ejecutó `clean verify`: 568/568 PASS, 0 fallos, 0 errores, 0 omitidos; JaCoCo informó que todos sus checks se cumplieron. Frontend, detección, estructura y Terraform también pasaron. El job backend y `ci-required` terminaron rojos en el paso operativo `Validate PDF and bulk HTTP flows on PostgreSQL`: `POST /tax-configurations/1/approve` respondió 403 `SELF_APPROVAL_FORBIDDEN`.

La aceptación automatizada crea y envía una configuración y luego intenta aprobarla usando la misma sesión. La protección de negocio contra autoaprobación actuó correctamente. No se debilitó esa regla. El siguiente ajuste corresponde al runner de aceptación: usar un actor RENTAS para crear/enviar y un SUPERVISOR distinto para aprobar. El runner `scripts/test/aws_test_acceptance.py` fue originalmente aportado por `pNico1`; se solicitó su revisión en el PR #78 y no se modificó.

El paso `Render PDF evidence` pasó en el run nuevo, porque el Maven exitoso permitió instalar Poppler antes del render. En el run previo falló en cascada al omitirse esa instalación tras Maven fallido. No se modificó `.github/workflows/ci.yml`.

## SCRUM-807 — PIT

La sonda de `PaymentPlanService` elimina 11 de 12 mutantes y deja uno `NO_COVERAGE`; dos ejecuciones anteriores no tuvieron error de minion ni timeout. No se modificó el gate: 14 clases objetivo, mutadores PIT predeterminados, `mutationThreshold=60`, sin `targetTests`, y timeout constante de 60 segundos.

El workflow experimental aislado propuesto usa Java 17, un thread y una sonda de una clase. Conserva el POM temporal y los reportes de la sonda, pero no es una ejecución global ni está autorizado/ejecutado remotamente. SCRUM-807 continúa NO VERIFICABLE hasta tener el PIT global y sus reportes HTML, XML y CSV completos.

## SCRUM-793 — Dependencias y riesgo

Siguen sin declararse corregidos los cuatro hallazgos Spring: CVE-2026-47890, CVE-2026-47884, CVE-2026-47834 y CVE-2026-47860. El análisis de código no encontró las precondiciones de explotación documentadas para varias rutas, pero la versión efectiva permanece afectada; eso es evidencia de exposición reducida, no una corrección. Seguridad debe resolver cada CVE o aprobar una actualización compatible. No se usaron parches Enterprise-only sin disponibilidad confirmada.

GitHub informó al publicar que `develop` tenía ocho alertas Dependabot (dos altas y seis moderadas). Al corte de este informe falta el inventario individual por paquete/manifiesto; `npm audit` no sustituye el análisis de dependencias Maven.

## Migraciones y entornos

V29 está en `db/migration-demo`. `application-prod.yml` define ubicaciones productivas sin incluir `migration-demo`. V24–V28 y V30–V31 se conservan en sus ubicaciones. No se modificó la base compartida `rentas`.

## Revisiones necesarias antes de merge

- **Product Owner:** contratos, UUID y migraciones heredadas; confirmar compatibilidad de la cadena de PR.
- **DevOps:** workflow PIT experimental, cambio al artefacto JaCoCo, runner/costo y retención de logs.
- **Seguridad:** decisión documentada para cada CVE y revisión de artefactos/logs.
- **Frontend y QA:** aprobación de sus cambios y evidencia aplicable.
- **Autores de commits incorporados:** revisión de cambios bajo su ownership.
- **QA/aceptación:** responder a la revisión solicitada por el fallo `SELF_APPROVAL_FORBIDDEN`; el test debe usar actores distintos sin debilitar el control.
- **CI:** `clean verify`/JaCoCo en `2eb704dd` pasó, pero la aceptación PDF/bulk falló y `ci-required` quedó rojo. Repetir tras la corrección del runner. No se tocó el workflow habitual.

## Decisión

**GO para revisión en Draft. NO-GO para merge** hasta obtener un PIT global válido con score >=60%, una decisión de Seguridad para los cuatro CVE, revisión DevOps y de los propietarios afectados, y CI completamente verde sobre el SHA final. Core/M9 externo y su ACK siguen sin validar.

## Informe PDF

El PDF profesional adjunto actualiza la portada y el registro final de estado. Las páginas centrales son históricas; la portada y la página 12 registran el CI actual: Maven/JaCoCo PASS, aceptación HTTP FAIL por autoaprobación, con revisión solicitada a su autor. El commit documental no modifica código.
