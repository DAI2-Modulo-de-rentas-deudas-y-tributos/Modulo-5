# Anexo — Uso de herramientas de IA en M5 Rentas

**Corte documentado:** 27 de octubre de 2026
**Repositorio:** Módulo 5 — Rentas, Deudas y Tributos
**Propósito:** dejar claro qué asistencia de IA puede verificarse en este entorno y qué historial falta para una transcripción completa.

## Alcance y límites de este registro

Este anexo es un registro resumido, no una transcripción literal de todas las conversaciones del proyecto. No se dispone aquí de una exportación íntegra de prompts y respuestas históricos ni de un registro verificable por fecha para cada intervención. Por eso, las descripciones históricas se presentan como resúmenes; no se reconstruyen frases como si fueran prompts exactos.

El prompt completo de cierre recibido el 27/10/2026 sí está disponible en el historial de esta tarea. Su objetivo fue cerrar seguridad OWASP y rate limiting, revisar patrones y contratos de Core/M9, corregir textos por rol, validar frontend/backend/PostgreSQL/RabbitMQ, producir un manual y preparar un anexo transparente. Indicó expresamente no tocar ramas del PO, PR #70/#71/#73, Core real, RabbitMQ compartido, Jira ni la base `rentas` real.

Extractos literales representativos de ese prompt:

> “No conectar automáticamente el Outbox económico al cliente HTTP externo.”
>
> “No enviar al Core real sin autorización explícita del equipo y credenciales válidas.”

La respuesta de Codex para esta etapa se resume fielmente en el registro de cambios y validaciones de abajo; no se presenta como transcripción íntegra.

Para completar un registro literal de las etapas anteriores, el equipo debe aportar la exportación de conversaciones de Codex y cualquier salida original de Graphify que quiera citar. Hasta entonces, no se atribuyen respuestas textuales ni decisiones personales que no tengan evidencia.

## Registro verificable de esta etapa

| Herramienta | Fecha | Objetivo | Resultado o resumen fiel | Evidencia/cambios | Validación humana y límites |
|---|---|---|---|---|---|
| Codex | 27/10/2026 | Cierre local de seguridad, patrones, contratos, portales y validación integral. | Se completaron correcciones acotadas en textos de Integraciones y Pagos; se ajustó un test de UI; se corrigió la prueba que fuerza perfiles de Flyway; se agregó una regresión para verificar el reset del contador después de login exitoso; se documentó esa evidencia. | Cambios de esta rama en `frontend/src/pages/rentas/EventosPage.jsx`, `frontend/src/pages/rentas/PagosPage.jsx`, `frontend/src/__tests__/modulos.test.jsx`, `backend/src/test/java/ar/gob/municipalidad/rentas/M8ProductionPostgreSqlMigrationTest.java`, `backend/src/test/java/ar/gob/municipalidad/rentas/PostgreSqlIntegrationTest.java` y `docs/SEGURIDAD_CRITERIOS_ACEPTACION.md`. Evidencia E2E en `docs/integrations/evidence/m5-final-security-ux-core-20261027/`. | Se ejecutaron suites y pruebas reproducibles descritas en el informe final. Codex inspeccionó los diffs y capturas; el usuario/equipo conserva la responsabilidad de aprobar los cambios. No se hizo commit, push, PR, merge ni deploy. |
| Codex | 27/10/2026 | Preparar manual de usuario y este anexo, después de estabilizar el código. | Se generaron un manual editable DOCX y su PDF. Las capturas son de una ejecución E2E local con datos sintéticos. Este anexo declara que no hay transcripciones históricas completas disponibles en el entorno. | `docs/MANUAL_USUARIO_M5.docx`, `docs/MANUAL_USUARIO_M5.pdf` y este archivo. | El PDF fue exportado desde Microsoft Word y revisado visualmente página por página. No se incluyeron contraseñas, tokens ni datos personales reales. El manual indica que el pago es simulado y que aceptación del broker local no demuestra entrega a Core. |

### Cambios técnicos destacados de la etapa

- Se mantuvo el limitador de intentos de login DEMO compartido mediante PostgreSQL y se reforzó su evidencia de prueba: tras un login válido, los fallos posteriores comienzan una ventana nueva. La migración DEMO V29 no se aplica en producción; V24–V28 no se reescribieron.
- Se revisó que la prueba del perfil productivo use la configuración efectiva de `application-prod.yml` en vez de imponer ubicaciones Flyway desde el test.
- Se aclararon textos de interfaz sobre DLQ y reversiones para distinguir la política de cuatro entregas comunicada por M9 del estado local y de la entrega externa aún no verificada.
- Se conservó el límite entre `BROKER_CONFIRMED` local y la entrega/aceptación externa de Core, que no fue verificada.

## Claude Code — sesión del PO del 02 y 03/11/2026

**Herramienta:** Claude Code (aplicación de escritorio), modelo Claude Opus 5.5. Los commits que
produjo llevan `Co-Authored-By: Claude Opus 5.5` y se publicaron con la identidad Git del PO, que
los revisó y es responsable de explicarlos.

**Prompts representativos (literales del PO):**

> “revisa si el proyecto cumple con las condiciones de este documento”
>
> “planifica un orden para implementar todo lo que queda implementar en el proyecto respecto a los requisitos del documento”

| Objetivo | Qué se aceptó | Cómo se verificó |
|---|---|---|
| G1: sistema completo con un comando | `compose.yaml` con RabbitMQ, backend, frontend y usuarios/datos DEMO; `.env.example` comentado; corte con mensaje si falta una variable. | Job `compose` del CI desde clon limpio; arranque local desde cero (412 s). |
| G6: secretos en el historial | gitleaks bloqueante y trufflehog informativo en el CI; `.gitleaksignore` con las huellas de 5 falsos positivos; `docs/security/G6_ESCANEO_SECRETOS.md`. | Escaneo del historial completo en el CI; huellas calculadas en local y confirmadas por el CI. |
| SCRUM-818 | Exclusión de `UserDetailsServiceAutoConfiguration` para que Spring no imprima una contraseña. | El test nuevo falla sin el arreglo y pasa con él; el log del backend ya no la muestra. |
| Ejes A–D | E2E con Playwright y axe, Schemathesis, resiliencia del broker, PIT, k6, ZAP y estabilidad (ver `README.md`, Automatización). | Cada script se corrió contra el stack local antes de subirlo; el CI del PR #101. |

**Diagnósticos incorrectos de la IA y cómo se detectaron:**

1. **G6:** afirmó que los 11 hallazgos JDBC de trufflehog eran contraseñas reales. Al replicar la
   regex de trufflehog sobre esos commits se vio que eran URLs sin credenciales. Se corrigió antes de
   reescribir el historial y el PO decidió documentar en lugar de reescribir.
2. **Pruebas de contrato:** la primera corrida de Schemathesis dio 401 en 164 operaciones y parecía
   un problema de autenticación del backend. La causa era que Schemathesis llamaba a
   `POST /dev-auth/logout` con la sesión de la prueba; se excluyó `dev-auth`.
3. **ZAP:** concluyó que el escaneo activo había perdido la sesión. Era un artefacto de Git Bash en
   Windows (`curl` no podía escribir en `/dev/null`, código 23); los últimos requests autenticados
   del escaneo respondían 200.

**Qué se descartó:** reescribir el historial de Git por G6; correr PIT en cada PR (no entra en los
15 minutos del pipeline; quedó en un workflow semanal).

### Plan de IA para la 4.ª entrega (propuesta a validar por el equipo)

| Herramienta | Objetivo medible | Riesgo que controla |
|---|---|---|
| Claude Code como revisor de cada PR (`/code-review`) | 0 bugs Blocker/Critical nuevos en SonarCloud al mergear. | Defectos que los tests no cubren; cada hallazgo se acepta sólo con un test que lo reproduzca. |
| Generación de escenarios Gherkin desde las HU, revisados por QA | ≥ 40 % de casos negativos o de borde en la especificación. | Huecos en la matriz de trazabilidad. |
| Triage asistido de los reportes de PIT y Schemathesis | Mutantes sobrevivientes del dominio clasificados en menos de una semana. | Tests que pasan aunque se borre el código que dicen probar. |

## Usos históricos conocidos, no disponibles como transcripción

El historial visible del proyecto contiene varias etapas de trabajo asistido sobre UUID económicos, validación local PostgreSQL, RabbitMQ, PR apilados, seguridad, patrones y auditoría de portales. En este anexo se registran sólo como **categorías resumidas** porque no existe una exportación completa que permita verificar prompt, respuesta, fecha y archivos de cada turno:

1. **Persistencia de identificadores y conciliación diferida:** se trabajó sobre UUID persistentes y recuperación de eventos sin inventar identidad canónica de M1. La rama del PO y los documentos de traspaso son evidencia del contexto, pero no sustituyen el historial literal de IA.
2. **Transporte local RabbitMQ/Core:** se validó publicación local desde Outbox, sin afirmar que Core real recibiera o procesara eventos. La evidencia E2E actual sólo prueba el broker efímero.
3. **Seguridad y patrones:** se revisaron controles DEMO, criterios OWASP y ADRs. Los archivos actuales demuestran el estado documental/código, pero no permiten reconstruir literalmente los intercambios anteriores.
4. **Revisión de portales y pruebas E2E:** la ejecución del 27/10/2026 tiene artefactos verificables; las sesiones anteriores no tienen aquí un manifiesto íntegro para atribuir resultados por fecha.

No se deben interpretar estos puntos como prompts exactos, respuestas completas ni aceptación del PO o de módulos externos.

## Graphify

Graphify se ejecutó localmente el 27/10/2026 en modo sólo código (`GRAPHIFY_BACKEND=none`), sin extracción semántica ni envío a Cloud. La versión instalada fue `graphifyy 0.9.96`. Procesó 407 archivos de código y produjo localmente 4.262 nodos, 17.165 relaciones y 165 comunidades; el registro indica 0 tokens de entrada y salida. El reporte señaló `ApiController` como nodo de navegación con mayor conectividad (193 relaciones), además de no detectar ciclos de importación. Son pistas estructurales para explorar, no evidencia de corrección ni auditoría de seguridad.

- **Uso en Cloud:** no se usó; el recorrido fue local y el backend se fijó en `none`.
- **Alcance incompleto:** los parsers opcionales `tree-sitter-sql` y `tree-sitter-hcl` no estaban instalados; por ello Graphify no analizó 40 archivos SQL y 42 archivos Terraform/HCL. Los documentos, imágenes y PDF se excluyeron deliberadamente del modo sólo código.
- **Artefactos:** un resumen no confidencial y duradero está en `docs/integrations/evidence/m5-final-security-ux-core-20261027/GRAPHIFY_SUMMARY.md`. El grafo y el reporte completos permanecen en Temp local, no se incluyen porque el grafo completo no es necesario para la revisión.
- **Integración de Codex:** se registró una skill de proyecto en `.codex/skills/graphify/`, una configuración de hook en `.codex/hooks.json` y una instrucción condicional en `AGENTS.md`. El hook requiere que el ejecutable `graphify` esté disponible en PATH; esta condición debe verificarse en otros equipos.

## Validaciones de esta entrega

Resultados registrados para el código de esta etapa:

- Backend `clean verify`: BUILD SUCCESS, 450 tests, 0 failures, 0 errors, 0 skipped; Testcontainers con PostgreSQL 17.11 y RabbitMQ 4.3. JaCoCo: 90,80% líneas, 90,01% instrucciones y 65,27% ramas; gate de líneas >=85% aprobado.
- Flyway: V28 común; V29 sólo en los perfiles DEMO/test. El perfil productivo valida con V28 y excluye la migración DEMO.
- Frontend: 367/367 tests en 47 archivos; build exitoso; `npm audit --audit-level=low` informó 0 vulnerabilidades.
- Cobertura frontend local: líneas 85,75% (3.124/3.643), statements 83,90% (3.520/4.195), funciones 81,12% (1.350/1.664) y branches 73,51% (2.884/3.923). La meta académica de líneas >=85% se alcanza en esta ejecución.
- E2E Playwright local: 31/31 aserciones PASS, con PostgreSQL y RabbitMQ efímeros; la evidencia de esta etapa está en `docs/integrations/evidence/m5-final-security-ux-core-20261027/`. El manifiesto identifica HEAD `9719007643e39c7b18266ae9c6c35426da1b8265`, pero no registra hash/limpieza del árbol de trabajo; no puede probarse si incluyó todos los cambios locales de UI de este PR. No se repitió después de la última ronda. El broker confirmó publicación local; Core no fue conectado.
- El bundle de frontend continúa sobre el umbral de advertencia de Vite (~1,12 MB sin comprimir).

Las cifras son evidencia de ejecución local y no equivalen a interoperabilidad externa, despliegue ni aprobación académica.

## Revisión humana pendiente

El equipo debe revisar los diffs y decidir la publicación. También debe solicitar al responsable de la consigna y al equipo PO:

- la exportación íntegra de conversaciones que se requiera para un anexo literal;
- los artefactos originales de Graphify si deben formar parte de la evidencia;
- la especificación BRD/Gherkin original y la matriz oficial de trazabilidad, ausentes de los archivos disponibles durante esta tarea.

Hasta recibir esos materiales, no se inventan historias, criterios ni transcripciones.
