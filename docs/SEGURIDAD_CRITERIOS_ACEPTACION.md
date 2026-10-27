# M5 Rentas — criterios verificables de seguridad

`PASS` significa que hay una prueba local que ejercita el criterio. `BLOCKED`
significa que el contrato o entorno requerido pertenece a otro equipo y no se
simuló como si fuese real. Las pruebas locales no equivalen a interoperabilidad
externa.

## Web/API

1. **OpenAPI limitado en producción — PASS.**
   - **Dado** el perfil `prod` y una solicitud anónima.
   - **Cuando** consulta la especificación OpenAPI o su interfaz.
   - **Entonces** recibe `401`, mientras las rutas de health permanecen públicas.
   - Evidencia: `ProductionSecurityDocumentationTests`.
   Referencia: OWASP ASVS V4/V13.
2. **Login DEMO con límites distribuidos — PASS local.**
   - **Dado** el mismo usuario y dirección de origen.
   - **Cuando** se realizan cinco intentos fallidos dentro de quince minutos, y luego un sexto.
   - **Entonces** los cinco primeros reciben `INVALID_CREDENTIALS` y el sexto `429` con
     `Retry-After: 900`; un login válido reinicia la ventana.
   - El contador
   usa una escritura atómica PostgreSQL y sobrevive a instancias que comparten
   esa base. Evidencia: `PostgreSqlIntegrationTest.demoLoginRateLimitAllowsFiveFailuresThenReturnsRetryAfter`,
   `demoLoginRateLimitIsAtomicUnderConcurrentFailures` y
   `demoLoginRateLimitExpiresAfterFifteenMinutesAndIsSharedAcrossInstances`;
   `successfulDemoLoginResetsTheFailureWindow` comprueba el reinicio tras login válido.
   Aplica sólo a login/bootstrap DEMO; no se presenta como limitador general de
   API ni como protección de autenticación Core. Referencia: OWASP ASVS V2/V6.
3. **Sin identidad falsificada por cabeceras — PASS.**
   - **Dado** un cliente sin sesión válida.
   - **Cuando** envía cabeceras `X-Dev-*` a una ruta protegida.
   - **Entonces** la API no le asigna identidad privilegiada.
   - Evidencia: `SecurityTests.missingSessionDoesNotGrantPrivilegedIdentity`.
   Referencia: OWASP API Security — Broken Authentication.
4. **Separación de roles de escritura — PASS.**
   - **Dado** un usuario autenticado con rol `AUDITOR`.
   - **Cuando** intenta ejecutar una operación de escritura.
   - **Entonces** la API la rechaza y no concede el permiso.
   - Evidencia: `SecurityTests.auditorCannotWrite`.
   Referencia: OWASP ASVS V8.
5. **Ownership BOLA/IDOR — PASS en rutas probadas.**
   - **Dado** un usuario `TAXPAYER` autenticado.
   - **Cuando** consulta deudas, boletas o resumen pertenecientes a otra persona.
   - **Entonces** la API deniega el acceso; los tests específicos también cubren pagos,
     planes y exenciones.
   - Evidencia: `SecurityTests` y tests de servicios/controllers por recurso.
   Referencia: OWASP API Security — Broken Object Level Authorization.
6. **CORS y correlación — PASS.**
   - **Dado** un origen permitido y otro no configurado.
   - **Cuando** ambos realizan una solicitud CORS y se genera una respuesta de error.
   - **Entonces** sólo el origen permitido recibe CORS y el error conserva el identificador
     de correlación.
   - Evidencia: `SecurityTests.corsAllowsConfiguredOriginAndRequiredHeaders` y
     `SecurityErrorContractTests`. Referencia: OWASP ASVS V13.

## Integración entre módulos

7. **Autenticación entrante Core/M1 — BLOCKED externo.**
   - **Dado** que aún no se entregaron el contrato de JWT, claves confiables ni reglas
     de expiración/renovación.
   - **Cuando** se solicita probar una identidad de servicio entrante.
   - **Entonces** la interoperabilidad queda bloqueada y no se acepta una cabecera de
     identidad arbitraria como sustituto.
   - Evidencia: configuración actual de `SecurityConfig`; contrato y claves externas
     no disponibles para la prueba.
   Referencia: OWASP ASVS V2/V6.
8. **Entrega saliente/ACK Core — BLOCKED externo.**
   - **Dado** el Outbox y el RabbitMQ local aislado.
   - **Cuando** el broker local confirma la publicación.
   - **Entonces** sólo se registra `BROKER_CONFIRMED`; la aceptación de Core y el ACK
     del consumidor siguen sin probarse hasta recibir routing, TLS/AMQPS, DLQ y entorno
     del equipo M9.
   - Evidencia: pruebas locales del Outbox; no hay sesión externa de Core.
   - Referencia: OWASP ASVS V13/V14.

## Datos sensibles

9. **Contraseña/sesión DEMO — PASS local.**
   - **Dado** un usuario DEMO que inicia sesión.
   - **Cuando** se almacena su credencial y se crea una sesión.
   - **Entonces** la contraseña queda protegida con BCrypt, el token opaco no se almacena
     en claro y la sesión expira o puede revocarse.
   - Evidencia:
   `DemoAuthService`, `SecurityTests.loginIssuesRandomOpaqueTokenAndRejectsInactiveUser`,
   `expiredAndRevokedSessionsAreRejected` y
   `demoAuthHttpResponsesExposeOnlyTheDesignedLoginTokenAndNeverCredentialMaterial`.
   Referencia: OWASP ASVS V6/V7.
10. **Errores y logs sin datos sensibles — PASS local.**
    - **Dado** un error inesperado durante una solicitud que contiene datos sensibles.
    - **Cuando** la API devuelve el error y registra el diagnóstico.
    - **Entonces** la respuesta no expone SQL, detalles internos ni stacktrace; el log usa
      correlación y tipo de excepción sin DNI/CUIT/tarjeta.
    - Evidencia: `BoundaryHardeningTests` y
    `OperationalLogPrivacyTests`. Referencia: OWASP ASVS V7/V16.

## Límites y riesgos residuales

- El limitador V29 sólo existe en `db/migration-demo`; `M8ProductionPostgreSqlMigrationTest`
  ejecuta las ubicaciones reales de `application-prod.yml` y comprueba que V29 y
  su tabla no estén presentes.
- La ventana de bloqueo expira automáticamente; no es un bloqueo permanente de
  cuenta. El origen usa `HttpServletRequest.remoteAddr`; no confía en
  `X-Forwarded-For` sin un proxy confiable configurado.
- Este límite protege login/bootstrap DEMO, no APIs costosas ni autenticación
  Core/JWT.
- El token DEMO permanece en `sessionStorage`; una vulnerabilidad XSS podría
  leerlo. No se cambia a cookies/JWT sin diseño y pruebas de autenticación
  acordados.
- No se configuró CSP en el backend porque el HTML/JS puede servirse por Vite o
  por hosting separado. Definir CSP en el proxy/hosting que entrega el frontend.
- No se probó TLS/AMQPS contra Core; el entorno local no representa el firewall,
  truststore ni credenciales de M9.

## Evidencia de validación de esta rama

Validación local de esta rama, 27/10/2026:

- Backend `./mvnw clean verify`: BUILD SUCCESS, 450 tests, 0 failures, 0 errors,
  0 skipped; Testcontainers PostgreSQL 17.11 y RabbitMQ 4.3 PASS. JaCoCo: 90,80%
  líneas, 90,01% instrucciones, 65,27% ramas; gate de líneas >=85% PASS.
- Frontend `npm test`: 367/367 tests en 47 archivos PASS. `npm run test:coverage`:
  líneas 85,75%, statements 83,90%, funciones 81,12%, branches 73,51%.
- `npm run build`: PASS. `npm audit --audit-level=low`: 0 vulnerabilidades.
- Playwright local: 31/31 aserciones PASS; véase el manifiesto y capturas en
  `docs/integrations/evidence/m5-final-security-ux-core-20261027/`. La ejecución
  usó PostgreSQL y RabbitMQ efímeros. No se conectó Core; la aceptación del broker
  local no demuestra entrega ni ACK externo.

Las pruebas Playwright preceden a la última ampliación de tests y documentación;
la aplicación no cambió después de esa ejecución. Estos resultados son locales y
no reutilizan métricas históricas de PR #73.

Referencias: OWASP ASVS 5.0.0, categorías V2, V4, V6, V7, V8, V13 y V16;
[OWASP API Security Top 10](https://owasp.org/API-Security/).
