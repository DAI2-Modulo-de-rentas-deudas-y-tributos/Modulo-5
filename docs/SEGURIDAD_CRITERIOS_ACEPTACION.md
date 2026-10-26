# M5 Rentas — criterios de aceptación de seguridad

Alcance: controles web/API y autenticación DEMO existentes, más límites aún pendientes de integración. `PASS` significa que la prueba citada se ejecutó efectivamente en esta rama. Backend compilado con `--release 17` usando JDK 21.0.12.1. La verificación completa ejecutó 434 tests, incluyendo PostgreSQL 17.11/Testcontainers; JaCoCo: líneas 91,61%, instrucciones 90,32%, ramas 65,51%.

## Web/API

1. **Documentación OpenAPI cerrada en producción — PASS.**
   - Dado el perfil `prod` y una solicitud sin identidad autenticada, cuando se consultan `/v3/api-docs`, `/swagger-ui/index.html` o `/swagger-ui.html`, entonces se responde `401`; `/actuator/health` y `/api/v1/health` permanecen públicos (`200`).
   - Evidencia: `backend/src/main/java/ar/gob/municipalidad/rentas/SecurityConfig.java`; `backend/src/test/java/ar/gob/municipalidad/rentas/ProductionSecurityDocumentationTests.java` (1 test ejecutado y aprobado).

2. **OpenAPI accesible en TEST — PASS.**
   - Dado el perfil `test`, cuando se consulta `/v3/api-docs` sin sesión, entonces responde `200` y conserva el esquema OpenAPI esperado.
   - Evidencia: `backend/src/test/java/ar/gob/municipalidad/rentas/SecurityTests.java`, `healthAndOpenApiArePublic` (ejecutado y aprobado).

3. **Autenticación no falsificable con cabeceras de desarrollo — PASS.**
   - Dado un cliente sin sesión válida, cuando envía cabeceras `X-Dev-User`, `X-Dev-Roles` o `X-Dev-Taxpayer-Id`, entonces no obtiene identidad privilegiada y la API protegida responde `401`.
   - Evidencia: `backend/src/test/java/ar/gob/municipalidad/rentas/SecurityTests.java`, `missingSessionDoesNotGrantPrivilegedIdentity` (ejecutado y aprobado).

4. **Separación de permisos de escritura — PASS.**
   - Dado un usuario `AUDITOR`, cuando intenta crear un concepto tributario, entonces recibe `403` y la escritura no se autoriza.
   - Evidencia: `backend/src/main/java/ar/gob/municipalidad/rentas/ApiController.java`; `backend/src/test/java/ar/gob/municipalidad/rentas/SecurityTests.java`, `auditorCannotWrite` (ejecutado y aprobado).

5. **Aislamiento BOLA/IDOR de recursos del contribuyente — PASS en rutas cubiertas.**
   - Dado un `TAXPAYER` autenticado, cuando consulta resumen, deudas o boletas de otro contribuyente, entonces recibe `403`; sus recursos propios siguen disponibles.
   - Evidencia: `SecurityConfig.java` (`CurrentIdentity.requireOwnership`), `QueryServices.java`, `BillingServices.java`, `DebtHistoryService.java`, `PlanWorkflowService.java` y `ApiController.java`; tests `taxpayerCannotReadAnotherTaxpayer`, `taxpayerCannotPageAnotherTaxpayersDebts` y `taxpayerCannotReadAnotherTaxpayersBill` en `SecurityTests.java` (ejecutados y aprobados). La auditoría de código además verificó ownership en pagos/recibos/imputaciones, saldos/aplicaciones, planes/cuotas/solicitudes, exenciones/documentos/historial y beneficios.

6. **CORS limitado a orígenes configurados — PASS.**
   - Dado un origen local permitido, cuando realiza preflight con las cabeceras de sesión e idempotencia, entonces recibe los encabezados CORS configurados; un origen externo no recibe permiso.
   - Evidencia: `SecurityConfig.java`; `SecurityTests.corsAllowsConfiguredOriginAndRequiredHeaders` (ejecutado y aprobado).

## Integración entre módulos

7. **Autenticación entrante Core/M1 — PENDIENTE_EXTERNO.**
   - Dado un token o identidad de servicio de Core/M1, cuando una llamada entre módulos llega a M5, entonces debe validarse contra el contrato y las credenciales oficialmente acordados, sin confiar en cabeceras de identidad arbitrarias.
   - Evidencia actual: `SecurityConfig.java` sólo configura autenticación DEMO por sesión; no hay validación de JWT/Core en el alcance auditado. Falta contrato, material de confianza y prueba conjunta con el equipo externo; no se simuló ni se declara implementado.

8. **Entrega saliente y ACK de Core — PENDIENTE_EXTERNO.**
   - Dado un evento económico pendiente, cuando M5 lo publica hacia Core, entonces la autenticación del transporte, confirmación/ACK y estado de entrega deben reflejar el resultado real de Core, no sólo el registro local.
   - Evidencia: la suite de estas pruebas de seguridad no ejercita una conexión Core/RabbitMQ; la aceptación depende del contrato y entorno de Core. No habilitado ni verificado aquí.

## Datos sensibles

9. **Credenciales DEMO y sesiones protegidas — PASS.**
   - Dado el bootstrap/login DEMO, cuando se almacena la contraseña o se persiste la sesión, entonces la contraseña queda con BCrypt, el token opaco aleatorio no se almacena en claro (se guarda SHA-256), y la respuesta no revela hashes ni secretos; las sesiones expiran y pueden revocarse.
   - Evidencia: `backend/src/main/java/ar/gob/municipalidad/rentas/DemoAuthService.java`, `FullStackEntities.java`; `SecurityTests.loginIssuesRandomOpaqueTokenAndRejectsInactiveUser`, `expiredAndRevokedSessionsAreRejected` y `demoAuthHttpResponsesExposeOnlyTheDesignedLoginTokenAndNeverCredentialMaterial` (ejecutados y aprobados).

10. **Exclusión de autenticación y esquema DEMO en producción — PASS.**
    - Dado el perfil `prod`, cuando inicia la aplicación aun si se intenta habilitar el modo DEMO, entonces el arranque debe rechazar esa configuración y los beans/tablas DEMO no deben existir.
   - Evidencia: `ProductionSecurityBoundary` en `ProductionSchemaFilterProvider.java`, `application-prod.yml` y perfiles `@Profile("!prod")`; `ProductionSecurityBoundaryTest` y `ProductionSchemaIsolationTest` ejecutados y aprobados dentro de `clean verify`.

## Evidencia de prueba de esta revisión

- Comando: `./mvnw.cmd clean verify` desde `backend`; BUILD SUCCESS, 434 tests, 0 fallos, 0 errores, 0 omitidos. Testcontainers ejecutó PostgreSQL 17.11 y Flyway alcanzó V28.
- `SecurityTests`: 15 tests; `ProductionSecurityDocumentationTests`: 1; pruebas de aislamiento y boundary de producción incluidas en la suite completa; todas aprobadas.
- Cobertura JaCoCo final: líneas 91,61%; instrucciones 90,32%; ramas 65,51%; gate del proyecto aprobado.
- Intentos fallidos de login DEMO: no se añadió un limitador en memoria. En una aplicación con varias instancias, ese control sería evadible y una política por cuenta improvisada puede bloquear usuarios o revelar existencia. Para cumplir el umbral de 5 en 15 minutos con garantía, hace falta un contador compartido/atómico, expiración y respuesta uniforme; se recomienda acordar backend compartido antes de implementarlo.

Referencias: OWASP ASVS 5.0.0, especialmente V2 (validación/reglas de negocio), V4 (API y servicios web), V6 (autenticación), V7 (sesiones), V8 (autorización), V13 (configuración) y V16 (logs y errores). [Versión estable y descargas ASVS](https://asvs.owasp.org/downloads/).
