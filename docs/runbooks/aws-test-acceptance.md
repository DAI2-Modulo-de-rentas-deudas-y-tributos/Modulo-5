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
- bulk_size: 10, 50 o 200;
- max_duration_seconds: 60, 120 o 300;
- confirmation: RUN_QA_TESTS.

Aprobar el deployment del environment test cuando GitHub lo solicite.

La URL de la API se obtiene desde VITE_API_BASE_URL de la aplicación Amplify modulo-5-rentas-test-frontend. El workflow no necesita Terraform ni una URL ingresada manualmente.

## Autenticación

Mientras TEST conserve el perfil de desarrollo, el runner usa X-Dev-User y X-Dev-Roles.

Cuando Core/Auth entregue OIDC, definir el secret TEST_API_BEARER_TOKEN en el environment test. Si ese secret existe, el runner usa Authorization Bearer y deja de enviar los headers de desarrollo. El token debe representar un usuario de QA con roles RENTAS, SUPERVISOR y CASHIER.

Nunca guardar tokens en el repositorio ni imprimirlos en los logs.

## Escenario PDF

La prueba:

1. crea un concepto y una configuración exclusivos de la ejecución;
2. crea una liquidación y localiza la deuda generada;
3. emite una boleta;
4. descarga el documento;
5. valida HTTP, tipo de contenido, nombre del adjunto, firma PDF, número de boleta y total;
6. adjunta el PDF real como evidencia del workflow.

QA debe descargar el artifact y revisar visualmente legibilidad, textos, importes, fechas y nombre de archivo. La revisión visual sigue siendo manual.

## Escenario masivo

La prueba:

1. crea un concepto y una configuración exclusivos;
2. prepara la cantidad solicitada de ítems;
3. usa los contribuyentes existentes como casos válidos;
4. completa el lote con identificadores inexistentes para provocar errores controlados;
5. valida totales del preview y que todavía no haya liquidaciones;
6. envía, aprueba y ejecuta la corrida;
7. valida una liquidación por cada caso válido;
8. comprueba el tiempo máximo;
9. intenta ejecutar otra vez y exige un error HTTP;
10. verifica que no aparezcan duplicados.

La prueba automática de CI sí cubre 50 contribuyentes válidos. En AWS TEST, la cantidad de éxitos posibles depende de los contribuyentes cargados. Con el dato inicial actual normalmente habrá un caso válido y el resto serán errores controlados. Para una prueba real de volumen con 50 éxitos, QA debe cargar previamente 50 contribuyentes mediante el mecanismo acordado de integración o dataset.

## Evidencias

El workflow conserva durante 7 días:

- results.json con métricas y resultados;
- summary.md con el resumen;
- el PDF descargado cuando se ejecuta ese escenario.

El mismo resumen aparece en la página de la ejecución de GitHub Actions.

Las pruebas crean datos trazables con prefijo QA_ en PostgreSQL TEST. No los eliminan automáticamente para conservar evidencia. La limpieza se realiza destruyendo TEST durante una pausa larga o mediante un procedimiento funcional futuro.

## Apagado posterior

Al terminar la sesión de QA ejecutar Control AWS TEST runtime con:

- operation: stop
- confirmation: STOP_TEST

Esto deja ECS en desired count 0 y detiene RDS. Amplify y la infraestructura base permanecen creados.
