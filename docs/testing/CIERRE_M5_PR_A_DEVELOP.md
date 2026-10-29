# Módulo 5 / Rentas - preparación de PR hacia `develop`

**Fecha de corte: 29/10/2026. Estado: candidata local preparada; publicación y merge pendientes.**

## Alcance y verdad del snapshot

- Candidata revisada: rama local `integration/m5-final-develop-20261028`; el código y workflow de esta etapa quedaron en `e9ee88a`, y este informe los documenta. `origin/develop` actualizado a `27e6f6d11181da870dc3dcb4c6edc14da5155100`. Tras agregar este informe, la rama tiene 57 commits y 302 archivos de diferencia; el PDF final agregará uno más. No está atrasada respecto de develop. El SHA candidato no está publicado y `ls-remote` no encuentra una rama remota candidata.
- Worktree experimental: `codex/scrum-807-pit-actions`, en el mismo HEAD base, no publicado. El paquete ZIP fue comparado con la propuesta previa; sus mejoras de autorización del trigger y minimización de logs fueron incorporadas localmente.
- Los cuatro cambios locales de la candidata fueron revisados: `backend/pom.xml`, `DebtHistoryTests.java`, `PaymentPlanServiceMutationTests.java` y `docs/security/SCRUM-793_SCA_2026-10-28.md`. Los tres primeros corresponden a pruebas/dependencias; el informe de SCA conserva explícitamente los cuatro riesgos Spring pendientes.
- La propuesta PIT, sus cuatro pruebas de contrato y la solicitud de decisión de Seguridad están presentes en la candidata local. Los tests del generador pasaron (4/4) y SnakeYAML pudo parsear el YAML; `actionlint` y la validación por GitHub Actions no se ejecutaron. No se publicó ni ejecutó el workflow.

## Secuencia de cierre recomendada

1. **Revisión funcional/Git:** obtener export verificable del worktree local de la candidata (o publicar en una rama aislada tras aprobación). Ejecutar `git status`, `git diff --stat`, `git diff --check`, `git rev-parse HEAD`, compararlo con `origin/develop`, reconciliar cada commit y las ramas/PR apiladas. Evitar reintroducir cambios ya mergeados por otros.
2. **Separar por responsabilidad:** pruebas SCRUM-807 (`DebtHistoryTests`, `PaymentPlanServiceMutationTests`); dependencias y SCA SCRUM-793 (`pom.xml`, informe); experimentación PIT sólo en rama `codex/scrum-807-pit-actions`. Evitar incluir por arrastre CI, frontend o migraciones de otros sin su aprobación.
3. **DevOps/Seguridad:** aprobar nuevo workflow experimental por `push`, retención de logs, uso de runner público y permisos. Registrar quién aprobó y fecha. No tocar CI habitual ni rama predeterminada.
4. **Snapshot remoto:** construir código exactamente idéntico a la candidata aprobada + infraestructura de sonda; verificar hashes de los cuatro archivos y documentar SHA de commit completo. Ejecutar sólo la sonda `PaymentPlanService` con commit marcado `[run-pit-probe]`. No usar reporte de esa clase para afirmar cobertura global.
5. **Análisis de sonda:** exigir `BUILD SUCCESS`, `0 MEMORY_ERROR`, ausencia de errores de infraestructura, reportes HTML/XML/CSV consistentes, tiempo y memoria con margen, denominador documentado. Revisar por mutante cualquier `TIMED_OUT`.
6. **Decisión global:** solo si DevOps aprueba costo/runner y la sonda fue estable, diseñar workflow **separado** para PIT global de 14 clases sin filtros de test. Gate completo: score >=60%, cero error de minion, reportes íntegros; no atribuir score a pruebas filtradas.
7. **Seguridad:** los cuatro CVE Spring continúan pendientes de actualización compatible o aceptación formal por el responsable del riesgo. Verificar ACL de broker antes de Core real. No autorizar riesgo en nombre de Seguridad.
8. **Revisiones inter-áreas:** DevOps para `.github/workflows/ci.yml`, frontend para rescates, QA para suite/cobertura, PO para migraciones/contratos y Core/M9 para integración real. Diferenciar validaciones locales de entregas externas/ACK no probados.
9. **Draft PR a `develop`:** cuando la rama remota y el SHA sean reproducibles, redactar PR con SHA, lista de commits/archivos, evidencia Maven/JaCoCo/PIT/SCA, riesgos y checklist de revisores. No publicarlo como "listo" mientras los gates estén abiertos.
10. **Merge:** después de revisiones y checks obligatorios, con autorización expresa y verificación de SHA/branch protection, coordinar merge. Actualizar el PDF con número de PR, commit merge, fecha y resultado real.

## Evidencia verificable aportada

| Criterio | Evidencia reportada | Interpretación |
|---|---|---|
| Maven Java 17 | Reportes Surefire del 28/10/2026 23:55: 568 tests, 0 fallas/errores/omitidos; el reporte registra Temurin 17.0.20.1 | Evidencia local histórica del worktree con sus cuatro cambios; no sustituye CI remoto |
| JaCoCo | XML del 28/10/2026: 91,80% líneas, 89,47% instrucciones y 66,70% ramas | Gate de líneas PASS en esa ejecución |
| PIT `PaymentPlanService` | 11/12 mutaciones, 1 sin cobertura, 0 errores/TO, dos repeticiones | Sonda parcial; no valida 60% global |
| PIT completo | sin reporte global válido; historia de 41 timeouts | SCRUM-807 NO VERIFICABLE |
| Dependencias | menores compatibles validadas; 4 CVE Spring residuales | SCRUM-793 BLOQUEADO hasta decisión |
| Integración Core/M9 | broker Rabbit local; ACK externo no probado | Pendiente contractual/E2E |
| GitHub Actions experimental | Cuatro tests del generador de POM PASS; YAML parseado por SnakeYAML | No se ejecutó actionlint ni Actions; pendiente revisión DevOps y Seguridad |

Al corte de esta actualización, la máquina tiene 2,12 GiB de RAM física libre, Docker Desktop no está disponible y el Java del shell es Temurin 21.0.12.1; no se repitió `clean verify` ni PIT. Los resultados anteriores son históricos y se presentan como tales.

## GitHub y responsables

- `develop` remoto observado: `27e6f6d11181da870dc3dcb4c6edc14da5155100`.
- El diff candidata/develop abarca 302 archivos antes de añadir el PDF final: backend 128, frontend 81, docs 67, contratos 21, workflows 2, scripts 2 y README 1.
- Hay tres commits de Rodrigo Blanco/RodriBlan en la ascendencia candidata; además la candidata agrega el reporte JaCoCo al artefacto de `.github/workflows/ci.yml`. Requiere revisión de DevOps y de los responsables de los cambios incorporados.
- PR #70, #71, #73 y #75 siguen abiertos como Draft; #74 y #76 aparecen mergeados. La integración candidata aún no tiene PR. Los checks automáticos de los PR consultados no reportaron estados.
- SCRUM-807 y SCRUM-793 siguen asignados a Santiago Nemi y en Jira figuran `Tareas por hacer`.

## Checklist para habilitar el PR

- [ ] Candidata local exportada y diff 57 commits reconciliado con `origin/develop` actualizado.
- [ ] Cuatro cambios locales revisados, testeados y comprometidos por separado.
- [ ] Nuevos commits de compañeros/PR apilados revisados por sus responsables.
- [ ] DevOps aprueba workflow, activación por push, runner, logs/artefactos y costo.
- [ ] Seguridad aprueba retención de logs y decide cada CVE residual.
- [ ] SHA remoto exacto corresponde al código y tests aprobados.
- [ ] Sonda PIT remota termina satisfactoriamente y publica artefactos válidos.
- [ ] PIT global completo ejecutado en procedimiento separado y score >=60% verificado.
- [ ] CI `clean verify`, JaCoCo y seguridad repetidos para SHA final.
- [ ] Se explicita límite de Core/M9 y revisión de PO sobre migraciones.
- [ ] Draft PR preparado con evidencia; revisores designados.
- [ ] Autorización final de merge a `develop` y controles de GitHub satisfechos.

## Texto breve sugerido para un eventual Draft PR

**Título:** `feat(m5): consolidar Rentas, integraciones y verificaciones de calidad` (adaptar al diff real).

**Resumen:** Consolidación del módulo Rentas con servicios de tributos, liquidaciones, pagos, planes, beneficios, recargos y eventos, más pruebas de dominio, auditoría y dependencias. El alcance real queda limitado al diff revisado entre `develop` y el SHA publicado. Se adjunta informe PDF de trazabilidad.

**Bloqueos:** no marcar como listo mientras SCRUM-807 no disponga de PIT global válido, SCRUM-793 no tenga resolución de riesgos residuales, o cambios de CI/PO/frontend/QA carezcan de revisión. No atribuir a E2E local una entrega/ACK de Core externo.

**Campos finales por completar tras cierre:** SHA HEAD candidato, SHA `develop` base, PR número, responsables y aprobaciones, enlace de Actions sonda/global, score PIT, decisión de 4 CVE, SHA del merge y fecha.
