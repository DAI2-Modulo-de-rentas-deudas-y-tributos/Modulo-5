# G6 — Escaneo de secretos en el historial

Fecha: 2026-11-02

Criterio: condición de admisibilidad G6 ("sin secretos en el repositorio en ningún commit del
historial", verificada por la cátedra con gitleaks / trufflehog en vivo) y escaneo de secretos
del informe de seguridad (Eje D).

## Cómo se ejecuta

El job `secret-scan` de [`ci.yml`](../../.github/workflows/ci.yml) corre en cada PR y en cada
push a `develop` y `main`, y cuelga de `ci-required`. Recorre el historial completo de todas las
ramas con la última versión publicada de cada herramienta:

| Herramienta | Modo | Efecto en el CI |
|---|---|---|
| gitleaks | `gitleaks git --redact` con la configuración por defecto y [`.gitleaksignore`](../../.gitleaksignore) | **Bloquea**: cualquier hallazgo nuevo hace fallar `ci-required`. |
| trufflehog | `trufflehog git --no-verification` | Informativo: publica los resultados como anotaciones. |

Los hallazgos se publican como anotaciones del run (`archivo:línea@commit` y regla), nunca con
el valor. trufflehog corre sin verificación para no enviar los candidatos a servicios externos.

Para reproducirlo localmente, desde la raíz de un clon completo:

```shell
gitleaks git --redact .
trufflehog git "file://$(pwd)" --no-verification
```

`gitleaks` toma `.gitleaksignore` de la raíz del repositorio sin configuración adicional.

## Resultados del primer escaneo

Base: rama `feature/g6-escaneo-secretos` sobre `develop` `ba0b77e`, todas las ramas remotas.
gitleaks 8.30.1: 5 hallazgos. trufflehog (imagen `latest` del 2026-11-02): 13 hallazgos no
verificados. Ninguno es una credencial.

### gitleaks — `generic-api-key` (5, falsos positivos)

| Archivo:línea | Commit | Qué es | Tratamiento |
|---|---|---|---|
| `ExternalPaymentEventsTests.java:370` | `c2dabd8` | Clave de idempotencia de prueba: prefijo fijo más un UUID aleatorio. | `.gitleaksignore` |
| `ExternalPaymentEventsTests.java:145` | `ca01aed` | La misma línea en una versión anterior del test. | `.gitleaksignore` |
| `OperationalLogPrivacyTests.java:131` | `b59cea9` | Datos ficticios que el test verifica que **no** aparezcan en los logs. | `.gitleaksignore` |
| `OperationalLogPrivacyTests.java:131` | `c2dabd8` | La misma línea en una versión posterior del test. | `.gitleaksignore` |
| `sonar-project.properties:5` | `6e84103` | Identificador público del proyecto en SonarQube Cloud. | `.gitleaksignore` |

La regla dispara por la combinación de una palabra clave (`key`, `token`) con un valor de
entropía alta. Con las huellas registradas, gitleaks queda en 0 hallazgos.

### trufflehog — resultados no verificados (13, falsos positivos)

| Detector | Cantidad | Qué es |
|---|---|---|
| `JDBC` | 11 | URLs `jdbc:postgresql://<host>:<puerto>/rentas` **sin usuario ni contraseña** en `ci.yml`, `compose.yaml`, `backend/compose.yaml`, `application.yml`, `backend/.env.example`, `pit-scrum-807-probe.yml` y `qa/levantar-demo.sh`. El detector informa toda URL `jdbc:`; se comprobó replicando su expresión regular sobre el historial, que da exactamente esos 11 archivos y commits. |
| `URI` | 2 | `frontend/src/lib/safeUrl.test.js:9`: URL de ejemplo con credenciales inventadas sobre el dominio reservado `example.test`, usada para probar que la aplicación **rechaza** URLs con credenciales. |

Las URLs JDBC son configuración necesaria: siguen en `application.yml`, `ci.yml` y
`compose.yaml`, y las credenciales viajan aparte por variables de entorno. Como ninguno de los
13 resultados contiene una credencial, no hay nada que trufflehog pueda verificar.

## Contraseñas locales en commits antiguos

Fuera de lo que detectan las herramientas, el historial conserva contraseñas de uso local o de CI
efímero escritas en commits anteriores a la condición G1:

- valores por defecto de PostgreSQL en versiones viejas de `compose.yaml`, `backend/compose.yaml`,
  `application.yml`, `.env.example` y `ci.yml` (base de servicio de CI, destruida en cada run);
- el secreto de bootstrap y la contraseña de base del script `qa/levantar-demo.sh`;
- contraseñas de los usuarios ficticios del dataset de prueba del frontend.

Ninguna se usó en un ambiente compartido: `infra/` nunca las referencia y los ambientes de AWS
toman sus credenciales de Secrets Manager. Desde el PR #91 la configuración de `develop` no tiene
contraseñas por defecto: cada una sale del `.env` local y el arranque se corta si falta. Siguen en
`develop`, como datos de prueba, las contraseñas ficticias de los tests del frontend (que corren
contra un backend falso en memoria) y la mención a la credencial de la base efímera de CI en
`docs/testing/SCRUM-807_PIT_GITHUB_ACTIONS.md`.

**Decisión**: no reescribir el historial. La reescritura obligaría a forzar el push de todas las
ramas, rompería los PR abiertos y los clones del equipo, y no cambiaría el resultado de
gitleaks ni de trufflehog. Si la cátedra pide la letra estricta ("ninguna contraseña en ningún
commit"), la reescritura con `git filter-repo --replace-text` queda como acción documentada.
