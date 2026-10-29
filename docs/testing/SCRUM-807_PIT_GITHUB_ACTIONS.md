# SCRUM-807 — Propuesta experimental de PIT en GitHub Actions

Estado: **propuesta local; no ejecutada en GitHub ni publicada** (2026-10-29). Preparada a partir del reporte de una rama aislada creada desde `508cef5da30d517fc4e8ac304d9f68415bf010ba`. Este SHA es la **base local histórica**, no el SHA definitivo de la sonda: el snapshot candidato original tiene cuatro cambios sin commit y no está publicado. Antes del primer push, incorporar los archivos aprobados y obtener el SHA nuevo del commit experimental; revisar ese diff exacto.

## Archivos propuestos

- `.github/workflows/pit-scrum-807-probe.yml`: push restringido a `codex/scrum-807-pit-actions`, con marcador obligatorio en el mensaje del último commit (`[run-pit-probe]`). Mantiene `workflow_dispatch` como opción futura.
- `.github/scripts/pit_scrum807_pom.py`: genera un POM descartable y valida que no cambió el gate base.

No se modifica `backend/pom.xml`, el workflow `CI`, ni otro workflow. El script exige que el perfil siga declarando exactamente las 14 clases aprobadas, `mutationThreshold=60`, mutadores predeterminados (sin `<mutators>`) y ninguna lista `targetTests`. Si detecta una diferencia, falla antes de PIT.

## Diferencias frente al CI actual

El workflow actual `.github/workflows/ci.yml` corre ante PR a `main`/`develop` y pushes a esas ramas, prepara Java Temurin 17 con caché Maven, inicia un servicio PostgreSQL 17 y ejecuta `mvn clean verify`. La propuesta:

- acepta únicamente `push` en la rama experimental y sólo lanza el job con marcador explícito; el futuro `workflow_dispatch` requiere `run-probe`, rama experimental y SHA esperado coincidente;
- reutiliza checkout, Java 17 y caché Maven del CI;
- reutiliza el mismo servicio efímero `postgres:17-alpine`, health check y credenciales ficticias del CI actual, además del Docker del runner que Testcontainers usa para sus pruebas aisladas;
- ejecuta `test-compile` y luego PIT, no toda la suite `clean verify`;
- añade telemetría de memoria, CPU, procesos Java **sin argumentos**, espacio de disco y contenedores, además de duración y logs; no recopila indiscriminadamente `docker logs` ni reportes Surefire;
- publica logs y reportes mediante artefacto aun cuando falle PIT o la comprobación posterior;
- limita permisos a `contents: read`, no usa secrets ni tokens externos y desactiva credenciales persistentes del checkout;
- usa una concurrencia por rama sin cancelar una ejecución en curso.

GitHub documenta para `ubuntu-latest` estándar de repositorios públicos 4 CPU, 16 GB RAM y 14 GB SSD; en repositorios privados el runner estándar documentado tiene 8 GB RAM. El repositorio es público al momento de preparar esta propuesta. El job tiene límite de 120 minutos y el paso PIT 105 minutos, dejando tiempo para inventario y subida de artefactos. Antes de compilar se exige al menos 5 GiB libres. La sonda corre con un único minion, no aumenta heap/concurrencia, y deja que PIT/Testcontainers consuman recursos dentro del runner efímero.

Referencias: [recursos de runners hosted](https://docs.github.com/en/actions/reference/runners/github-hosted-runners), [sintaxis y límites de workflows](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax), [configuración Maven oficial de PIT](https://pitest.org/quickstart/maven/).

## Sonda inicial

1. DevOps revisa YAML, políticas de Actions, consumo/costo, ejecución por `push` experimental y política de retención de artefactos. Seguridad revisa alcance de logs. No modificar `.github/workflows/ci.yml` ni rama predeterminada.
2. Preparar el snapshot de las **cuatro modificaciones locales** de la candidata: `backend/pom.xml`, `DebtHistoryTests.java`, `PaymentPlanServiceMutationTests.java` y `docs/security/SCRUM-793_SCA_2026-10-28.md`. Revisar diff/hashes; los cambios de seguridad sólo se incorporan si sus responsables los autorizan. Añadir workflow/script/documentación sobre el mismo código que se desea probar. No usar sólo el commit base `508cef5`.
3. Tras la autorización de commit/push, crear el commit de preparación **sin** marcador, anotar su SHA y validar el contenido remotamente; el primer push ordinario no ejecuta PIT. Obtener aprobación del SHA revisado.
4. Tras esa aprobación, publicar un commit explícito con mensaje que contenga `[run-pit-probe]` sobre `codex/scrum-807-pit-actions`. Este push inicia **únicamente la sonda**. Anotar SHA de ese commit y comprobar que la diferencia desde el snapshot aprobado sea sólo el cambio intencional. Si la política del equipo exige inmutabilidad absoluta, preparar directamente el commit marcado, verificar localmente su SHA y autorizar ese push único.
5. `workflow_dispatch` queda definido como respaldo futuro: **no podrá recibir eventos hasta que el workflow exista en la rama predeterminada**, por lo que no requiere cambiar `develop` para realizar la primera sonda.
4. La sonda acota sólo las clases mutadas a `PaymentPlanService*`; **no limita las pruebas**: no añade `targetTests`, por lo que PIT descubre/asocia las pruebas del classpath según su comportamiento habitual. No usar su score/denominador como resultado global.
6. Revisar el artefacto: `revision.txt` (ref y SHA inmutables), `runner-baseline.log`, `configuration.log`, `test-compile.log`, `pit.log`, `resource-telemetry.log`, `result.txt`, resumen no sensible de contenedores y archivos `index.html`, `mutations.xml`, `mutations.csv`. Confirmar consistencia y ausencia de interrupciones.

**Límite de seguridad:** revisar logs antes de redistribuir el artefacto; los tests podrían imprimir tokens o contraseñas generadas aun cuando el workflow no recibe secrets. GitHub Actions retiene registros y artefactos según las políticas del repositorio. Solicitar aprobación de Seguridad para el inventario de logs y acceso.

La ruta de artefactos queda retenida 14 días. Las credenciales `rentas_ci` son las mismas de la instancia efímera que ya declara CI; no son secretos. La ejecución no necesita Core/M9, RabbitMQ compartido ni la base local/compartida `rentas`.

## Procedimiento separado para PIT global

No usar el workflow de sonda para inferir el score total. Una ejecución global requiere aprobación explícita posterior y un workflow manual separado (no incluido aquí) que use el mismo runner, telemetría, límites de disco y captura de artefactos.

Comprobaciones previas obligatorias:

1. La sonda finaliza sin `MEMORY_ERROR`, errores de minion ni cancelación; cualquier `TIMED_OUT` queda clasificado con mutante, prueba y tiempos base, y no se confunde un límite de infraestructura con bucle no terminante.
2. Se verificó la consistencia entre XML, CSV y HTML de la sonda, la versión Java 17/PIT 1.18.2 y el POM efectivo.
3. DevOps confirma disponibilidad del runner, capacidad de disco, duración y política de artefactos; Seguridad confirma la retención de logs de pruebas.
4. El PR/workflow global no comienza hasta que el responsable apruebe el costo operativo y el universo global.

Comando global para un workflow dedicado aprobado: generar POM descartable con `--scope global`, ejecutar PIT sin `targetTests` adicional y archivar reportes. Ejemplo desde la raíz:

```bash
python3 .github/scripts/pit_scrum807_pom.py \
  --scope global --allow-global --write backend/pom.pit-scrum807.xml
trap 'rm -f backend/pom.pit-scrum807.xml' EXIT
./backend/mvnw -B -ntp -f backend/pom.pit-scrum807.xml \
  -Pmutation-testing -DskipTests test-compile
./backend/mvnw -B -ntp -f backend/pom.pit-scrum807.xml \
  -Pmutation-testing \
  -DreportsDirectory="$GITHUB_WORKSPACE/backend/target/pit-scrum807-reports" \
  org.pitest:pitest-maven:1.18.2:mutationCoverage
```

`global` conserva sin cambios las 14 clases objetivo del POM. El POM temporal sólo fija `threads=1`, `timeoutConstant=60000`, salida HTML/XML/CSV y un directorio reproducible. No agrega `<mutators>` (mantiene los mutadores predeterminados) ni `<targetTests>` (mantiene la selección de tests del gate). El perfil conserva `mutationThreshold=60`.

El score global sólo es válido si PIT termina normalmente sobre ese universo completo, registra el denominador y todas las categorías de mutantes en forma consistente, genera HTML/XML/CSV completos y Maven sale exitoso con el threshold 60 satisfecho. El score parcial de la sonda no reemplaza ese resultado. Si se interrumpe o faltan reportes, SCRUM-807 continúa NO VERIFICABLE.

## Riesgos y aprobaciones de DevOps

- `workflow_dispatch` no podrá ejecutarse hasta que el workflow esté disponible en la rama por defecto; se utiliza `push` de rama experimental para no requerir ese cambio. **No publicar** ni marcar el commit hasta autorización de DevOps y del responsable de la rama.
- El runner público estándar tiene 14 GB SSD. Dependencias Maven, clases compiladas, imagen PostgreSQL de Testcontainers, informes y logs compiten por ese espacio; el guardrail de 5 GiB es preventivo, no garantiza que la corrida global quepa. Un runner mayor no es necesario para la primera sonda, pero puede requerir plan de GitHub y aprobación/costo para un global.
- `ubuntu-latest` cambia su imagen con el tiempo; el artefacto registra Java, Maven, disco, memoria y Docker para reproducibilidad. El tag de PostgreSQL lo determina la configuración de tests vigente, no un servicio añadido por esta propuesta.
- El Job global puede exceder el máximo GitHub Actions de 360 minutos. Si una estimación reproducible excede ese límite, requiere runner mayor/dedicado aprobado; no se debe dividir el universo ni cambiar el denominador para forzar un score.
- `actions/upload-artifact` corre con `always()`, pero una terminación abrupta de la VM o cancelación del job puede impedir la subida final. Los logs se escriben durante la ejecución y el paso de PIT tiene un timeout menor al del job para reservar ventana de recolección.
- El repositorio actual del workflow sólo tiene `contents: read`; no requiere `pull-requests: write`, `actions: write`, `id-token`, secretos ni permisos de despliegue.
- El almacenamiento real se comprueba al arrancar y después de generar reportes; 14 GB nominales no son garantía de espacio útil. El guard de 5 GiB libres sólo protege antes de compilar y no evita llenar el disco durante PIT; `docker system df` e inventario permiten diagnosticarlo sin borrar recursos.

## Variables de entorno / secretos

Sin cambios en variables de entorno ni secretos. El workflow no define secretos ni consume variables secretas.
