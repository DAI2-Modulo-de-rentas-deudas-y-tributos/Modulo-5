# Login ciudadano de Rentas

El perfil `citizen-auth-local` y `RENTAS_CITIZEN_AUTH_ENABLED=true` son necesarios a la vez. Los perfiles `dev`, `staging`, `aws-test` y `prod` bloquean la integración. En un entorno local descartable:

- Configurá PostgreSQL local y aplicá Flyway, incluida V35. Usá un contribuyente sintético activo ya vinculado al UUID que devuelve el servidor M1 simulado.
- Configurá `RENTAS_CITIZEN_AUTH_BASE_URL=http://127.0.0.1:<puerto>` para el simulador y `VITE_AUTH_MODE=citizen`. Dejá `VITE_API_BASE_URL` vacío para usar el proxy de Vite. Serví el portal y la API bajo el mismo origen. La cookie siempre tiene Secure, incluso en local. Usá HTTPS local si el navegador no admite cookies Secure en localhost.
- El servidor expone `POST /api/v1/citizen-auth/login`, `GET /api/v1/citizen-auth/me` y `POST /api/v1/citizen-auth/logout`. Las peticiones mutables del modo ciudadano mandan `X-Rentas-CSRF: 1`. CORS no autoriza ese encabezado ni credenciales entre orígenes.
- El timeout predeterminado de conexión y lectura es 3 s. No se siguen redirecciones ni se reintenta el login en la aplicación. El presupuesto local es de cuatro intentos por IP de conexión por minuto, compartido en PostgreSQL. El backend no reenvía X-Forwarded-For.
- La sesión vence sin renovación a los 600 s por defecto (`RENTAS_CITIZEN_AUTH_SESSION_SECONDS`, rango 60–900). Logout revoca la sesión en M5. Cada solicitud verifica el vínculo y el estado local. Los tokens de M1 no se usan para autenticarse en M5.

Para repetir la prueba de HTTP y PostgreSQL con Java 17 y Docker:

```sh
cd backend
./mvnw -Dtest=CitizenSessionPostgreSqlTest,DemoLoginRateLimitHttpPostgreSqlTests test
```

**NO-GO para ambientes compartidos.** M1 y DevOps deben acordar una política de rate limiting para la IP de salida compartida y los proxies confiables. El límite de M5 no evita que los fallos de varios orígenes agoten un único presupuesto en M1. Queda pendiente cómo cerrar o revalidar sesiones de M5 cuando cambie el estado en M1 y cuál será el origen HTTPS del portal.

## Evidencia de infraestructura

La auditoría de solo lectura del 23/11/2026, comunicada por el equipo, observó:

| | TEST | PROD |
|---|---|---|
| Elastic IP de salida | 18.227.153.92 | 3.18.141.105 |
| Portal | test.modulo-rentas.com.ar | d161r0qnlqbgim.amplifyapp.com |
| API | d1637u7o1ntdmu.cloudfront.net | dk2759li8784m.cloudfront.net |
| Broker declarado | rabbitmq, consumidor habilitado | local-log, consumidor deshabilitado |
| PostgreSQL RDS | 16.13, detenido | 16.13 |

La auditoría observó NAT disponibles, rutas privadas activas y ECS sin IP pública. CloudFront llega al ALB por HTTPS. Amplify carece de reescritura `/api`. En TEST, ECS estaba en 0/0. La EIP de salida sirve para que M1 identifique a Rentas; no representa la IP del ciudadano y no prueba conectividad desde tareas activas.

El diseño se describe en [ADR de dominio](adr/0010-dominio-propio.md), [ADR de salida fija](adr/0011-ip-salida-fija-nat.md), [red](../infra/modules/network/main.tf), [hosting](../infra/modules/hosting/main.tf), [edge](../infra/modules/edge/main.tf) y [readiness de TEST](runbooks/aws-test-readiness.md). Antes de cualquier futura validación autorizada en TEST, DevOps debe resolver por separado la configuración AMQP: con tareas activas podría conectarse al broker configurado. Esta auditoría no activó recursos.

## Propuesta para un mismo origen

| Alternativa | Evaluación |
|---|---|
| Reescritura 200 en Amplify | Configuración más corta. La documentación da un ejemplo de proxy HTTPS. Antes de adoptarlo habría que demostrar que preserva métodos y cuerpos, `Cookie`, `Set-Cookie`, `Origin`, `Sec-Fetch-Site` y `X-Rentas-CSRF`, permite controlar caché y excluye `/api/*` de la reescritura SPA. |
| CloudFront unificado | Recomendado. Dos orígenes bajo un dominio de navegador, con comportamiento explícito para `/api/*` (y tratamiento de `/api`) aparte del frontend. Usar TLS hacia ambos orígenes y permitir GET, HEAD, OPTIONS, POST, PUT, PATCH, DELETE en API. Conservar códigos de error de API; aplicar fallback SPA solo al frontend. |

Para API, seleccionar `CachingDisabled`, caché de errores hasta 0 donde lo permita la configuración y `Cache-Control: no-store` para las respuestas de sesión y datos privados. La política de solicitud debe llevar parámetros, cookies y los encabezados de API necesarios, incluidos CSRF, Fetch Metadata, origen, idempotencia y precondiciones HTTP. Verificar Host/SNI del ALB, paso intacto de `Set-Cookie`, ausencia de logs de cookies y rechazo de acceso directo al origen. El ensayo de aceptación debe comparar dos sesiones con la misma URL y comprobar logout, POST/PATCH/DELETE, PDF y ausencia de respuestas cruzadas o caché. El proxy debe conservar `__Host-rentas-citizen` con Path=/ y sin Domain. Referencias: [reescritura en Amplify](https://docs.aws.amazon.com/amplify/latest/userguide/troubleshooting-redirects.html), [caché deshabilitada](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/using-managed-cache-policies.html), [política de solicitud](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/origin-request-understand-origin-request-policy.html) y [cookies](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/Cookies.html).

## Identidad y límites

- El límite ciudadano actual cuenta por conexión. `TrustedClientAddressResolver` ya ignora encabezados reenviados si el peer no está autorizado y recorre la cadena desde la derecha. Reutilizarlo cuando DevOps confirme la cadena del origen, CIDR de proxies y sanitización. La EIP NAT no es un CIDR de confianza para encabezados entrantes. Las peticiones del cliente M1 no reenvían `X-Forwarded-For`.
- `citizenUpdated/BLOCKED` y `DECEASED` cambian el estado local. La consulta de sesión requiere `ACTIVE`, por lo que una vez persistido el evento dejan de autorizar solicitudes. `UPDATED` solo actualiza datos personales en M5. No usar su estado informativo para reactivar ni suponer un evento independiente de reactivación. Sin entrega oportuna del evento, solo se cuenta con la expiración local.
- Acuerdos pendientes: política de rate limiting de M1 para la salida compartida, cadena de proxies y dominio HTTPS, entrega de cambios de estado y política de revocación, y cuenta sintética autorizada para E2E conjunta.

## Repetición de la verificación

```sh
cd backend
./mvnw -Dtest=CitizenV35FlywayUpgradeTest test
./mvnw -DskipTests package
cd ../frontend
npm ci
npx playwright install chromium
node e2e/run-citizen-https.mjs
```

El runner requiere Docker local, Java 17 y OpenSSL en PATH (o `OPENSSL_BIN`). Crea PostgreSQL 16.13 en loopback, un M1 sintético y un certificado efímero autofirmado que Chromium tolera solo en el contexto de prueba. Usa el JAR local y nunca apunta a un entorno compartido.
