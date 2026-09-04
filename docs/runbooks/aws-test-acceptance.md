# Pruebas de aceptación en AWS TEST

Este runbook cubre la validación de generación y descarga de boletas PDF y el procesamiento estable de operaciones masivas.

## Dos capas de prueba

### CI automático

El job de backend ejecuta Maven verify en cada pull request. OperationalAcceptanceTests valida:

- descarga HTTP de una boleta con Content-Type PDF y Content-Disposition de adjunto;
- firma del archivo, número de boleta y total esperado;
- denegación de descarga para un rol sin permiso;
- preview masivo de 50 contribuyentes válidos y un error controlado;
- ausencia de escrituras durante el preview;
- ejecución única de los casos válidos;
- conservación del error parcial;
- rechazo de una segunda ejecución para evitar duplicados;
- duración máxima de 30 segundos en el entorno de prueba del backend.

Estas pruebas son deterministas y no requieren AWS.

Para **SCRUM-139**, `PdfDownloadTests` amplía el contrato HTTP con permisos de
Rentas, Caja y contribuyente titular; denegación a otros roles, otro titular,
identidad incompleta, anónimos y headers de desarrollo falsificados con dev-mode
desactivado; HTTP 404; múltiples deudas; y descargas repetidas sin escrituras ni
cambios en los importes originales después de un pago.

Genera boletas de 1, 3 y 45 deudas en `backend/target/pdf-evidence/`. Los tests
Python leen esos archivos con **pypdf** en modo estricto y verifican páginas A4,
márgenes verticales, titular, fechas, número, cada deuda y total con centavos.
También comprueban que el validador rechace HTML, PDFs truncados, estructura
inválida, datos incorrectos y encabezados HTTP incorrectos. Poppler renderiza
todas las páginas a PNG. La boleta de 45 deudas debe tener dos páginas.

CI además inicia el JAR con PostgreSQL y perfil `dev` **sólo en su entorno
efímero**, ejecuta el mismo escenario HTTP `pdf` utilizado por TEST y conserva
PDFs, PNGs, JSON y reportes de Maven durante 7 días. Este bloque depende del job
`backend`, que ya es obligatorio para `ci-required`.

El job frontend prueba el botón compartido de Rentas y Portal: URL del backend,
identidad del contribuyente, descarga de un blob, nombre de archivo, bloqueo de
doble clic, liberación de recursos, errores HTTP/red y reintento. El modo demo
no ofrece descargar las referencias S3 ficticias. El perfil Core sigue pendiente
del contrato de autenticación: estas pruebas no implementan OIDC.

### Aceptación manual sobre AWS TEST

El workflow Run acceptance tests on AWS TEST prueba la aplicación ya desplegada, la red, el balanceador, ECS, RDS y los endpoints reales.

La ejecución usa el environment test, por lo que queda protegida por su aprobación. No inicia ni detiene TEST automáticamente.

## Preparación

1. Promover frontend y backend a TEST.
2. Ejecutar Control AWS TEST runtime con:
   - operation: start
   - confirmation: START_TEST
3. Esperar a que ECS tenga una tarea running y RDS esté available.
4. Verificar que TEST contenga al menos un contribuyente.

## Ejecución

En GitHub Actions abrir Run acceptance tests on AWS TEST y seleccionar:

- scenario: all, pdf o bulk;
- bulk_size: 10, 50 o 200 contribuyentes válidos (más un ítem inválido controlado);
- max_duration_seconds: 60, 120 o 300;
- confirmation: RUN_QA_TESTS.

Aprobar el deployment del environment test cuando GitHub lo solicite.

La URL de la API se obtiene desde VITE_API_BASE_URL de la aplicación Amplify modulo-5-rentas-test-frontend. El workflow no necesita Terraform ni una URL ingresada manualmente.
El runner acepta el origen (`https://api.example`) o el prefijo completo
(`https://api.example/api/v1`) y negocia tanto JSON como PDF.

## Autenticación

Mientras TEST conserve el perfil de desarrollo, el runner usa X-Dev-User y X-Dev-Roles.

Cuando Core/Auth entregue OIDC, definir el secret TEST_API_BEARER_TOKEN en el environment test. Si ese secret existe, el runner usa Authorization Bearer y deja de enviar los headers de desarrollo. El token debe representar un usuario de QA con roles RENTAS, SUPERVISOR y CASHIER.
Con Bearer, las pruebas que requieren cambiar de identidad se registran como
`skipped` explícitamente: harán falta tokens de titular, otro titular y auditor
para ejecutarlas en AWS. Los casos de autorización sí se ejecutan en CI con
Spring Security, sin asumir que los headers de desarrollo funcionan en producción.

Nunca guardar tokens en el repositorio ni imprimirlos en los logs.

## Escenario PDF

La prueba:

1. crea dos conceptos y configuraciones exclusivos por 100,25 y 250,50;
2. liquida ambos y localiza sus deudas;
3. emite una boleta individual y otra que reúne ambas deudas (total 350,75);
4. descarga y parsea los PDFs, verificando HTTP, adjunto, estructura, titular,
   fechas, cada deuda, importes y total;
5. repite las descargas y compara los bytes, sin alterar saldos ni estados de deuda;
6. exige HTTP 404 sin PDF para una boleta inexistente;
7. en modo dev, valida la descarga del titular y HTTP 403 sin PDF para auditor,
   otro titular y contribuyente sin identidad;
8. conserva los PDFs, incluso cuando la validación del documento falla, y los
   renderiza a PNG. El JSON incluye páginas, tamaño, hash SHA-256 y resultados.

QA debe descargar el artifact y revisar visualmente legibilidad, textos, importes, fechas y nombre de archivo. La revisión visual sigue siendo manual.
El resultado automático deja `visualReview: pending`. Para cerrar SCRUM-139,
adjuntar el enlace a una ejecución exitosa **en AWS TEST**, revisar los PNGs/PDFs
y probar el botón PDF en Rentas y Portal desde sus URLs desplegadas. Esta última
prueba cubre el navegador y CORS entre Amplify y la API; el runner HTTP no los
valida. S3 no es un prerrequisito: actualmente el backend genera el PDF al descargar.

### Reproducción local

```powershell
mvn -B -ntp -Dtest=PdfDownloadTests test --file backend/pom.xml
python -m pip install -r scripts/test/requirements.txt
python -m unittest discover -s scripts/test -p "test_*.py" -v
npm --prefix frontend test -- src/services/pdfDownload.test.js src/components/documentos/BillPdfDownload.test.jsx
```

Con la API de QA local levantada y al menos un contribuyente:

```powershell
python scripts/test/aws_test_acceptance.py --api-base-url http://localhost:8080 --scenario pdf --output-dir artifacts/qa-test
```

Usar una base de QA: el runner crea conceptos, liquidaciones y boletas y conserva
esos datos. La lectura de PDFs requiere Python 3.10+; CI fija Python 3.13.

## Escenario masivo

Corresponde a **SCRUM-117** y aporta evidencia del procesamiento aprobado para
**SCRUM-418**. Antes de crear datos exige 10, 50 o 200 contribuyentes distintos,
según `bulk_size`. Si faltan, falla: ya no reemplaza volumen real por errores.
En TEST, QA debe cargar ese dataset mediante el mecanismo acordado con M1.
El runner no crea ni modifica referencias maestras de contribuyentes.

La prueba:

1. crea concepto/configuración exclusivos y un lote con la población válida más
   un contribuyente inexistente para provocar un error controlado;
2. repite el preview, compara importes con centavos, población, contadores y
   errores, y comprueba que no existan liquidaciones ni deudas del concepto;
3. intenta ejecutar sin aprobar y exige HTTP 422 con `RUN_NOT_APPROVED`;
4. envía y aprueba el lote, y lanza **dos solicitudes de ejecución concurrentes**;
5. exige exactamente un HTTP 200 y un HTTP 422 con `RUN_NOT_APPROVED`;
6. consulta el estado persistido, cada ítem, sus importes y referencias, y exige
   exactamente una liquidación y una deuda por contribuyente válido;
7. verifica que el ítem inválido siga en ERROR, conserve código/mensaje y no tenga
   liquidación; los importes deben coincidir con el preview;
8. repite la ejecución y vuelve a exigir el rechazo de negocio y ausencia de
   cambios en liquidaciones/deudas. Un 401, 403, 404 o 5xx **no** cuenta como éxito;
9. comprueba el límite de tiempo y que el health check siga en UP.

El JSON conserva tamaño real, errores controlados, total monetario, tiempos de
preview y ejecución, tiempo total, respuestas concurrentes y resultado del health
check. El tiempo total medido va desde la creación de la corrida hasta recibir
las respuestas concurrentes; no incluye preparar el dataset ni las comprobaciones
posteriores. El timeout HTTP de ejecución se ajusta al límite elegido más 10 s.

### Cobertura automática y límites

`BulkStabilityTests` ejecuta 10/50/200 contribuyentes válidos más dos errores,
previews repetidos, integridad de deudas/importes y rechazo de reejecuciones.
Además cubre población duplicada, todos los ítems inválidos, estados no
autorizados, concurrencia, cambio de configuración y conflicto en el último ítem.
Usa transacciones que realmente confirman/revierten y una base H2 aislada.

La política actual de ejecución es **atómica**: si aparece un conflicto después
del preview, revierte toda la emisión del lote y queda APPROVED. Las liquidaciones
externas que causaron el conflicto permanecen intactas. Los errores detectados
durante el preview sí se excluyen y se conservan junto a los ítems exitosos.
Estas pruebas documentan ambas situaciones sin cambiar esa política.

`PostgreSqlIntegrationTest.concurrentBulkExecutionCommitsOnceOnPostgreSql`
comprueba el bloqueo con PostgreSQL 17 real mediante Testcontainers. Requiere
Docker; su ejecución local puede omitirse si Docker no está disponible.

CI carga 200 referencias sintéticas **sólo en su PostgreSQL efímero** con
`scripts/test/seed_bulk_ci.sql`, ejecuta el runner HTTP para los tamaños 10, 50 y
200, y publica `backend/target/bulk-evidence/` junto a los reportes. El workflow
manual TEST reutiliza el mismo runner y sus aprobaciones existentes.

```powershell
mvn -B -ntp "-Dtest=BulkStabilityTests,PostgreSqlIntegrationTest" clean test --file backend/pom.xml
python -m unittest discover -s scripts/test -p "test_bulk_acceptance.py" -v
# Sólo sobre una API de QA con al menos 50 contribuyentes distintos:
python scripts/test/aws_test_acceptance.py --api-base-url http://localhost:8080 --scenario bulk --bulk-size 50 --max-duration-seconds 120 --output-dir artifacts/qa-test
```

Para cerrar la tarea se necesita la ejecución exitosa sobre **AWS TEST** y su
evidencia. Los tests locales no prueban capacidad sostenida, múltiples usuarios
independientes ni tolerancia a la caída de ECS/RDS. Los tamaños son pruebas
acotadas de estabilidad, no un benchmark de capacidad de producción.

## Evidencias

El workflow conserva durante 7 días:

- results.json con métricas y resultados;
- summary.md con el resumen;
- los PDFs descargados y un PNG por página cuando se ejecuta ese escenario.

El mismo resumen aparece en la página de la ejecución de GitHub Actions.

Las pruebas crean datos trazables con prefijo QA_ en PostgreSQL TEST. No los eliminan automáticamente para conservar evidencia. La limpieza se realiza destruyendo TEST durante una pausa larga o mediante un procedimiento funcional futuro.

## Apagado posterior

Al terminar la sesión de QA ejecutar Control AWS TEST runtime con:

- operation: stop
- confirmation: STOP_TEST

Esto deja ECS en desired count 0 y detiene RDS. Amplify y la infraestructura base permanecen creados.
