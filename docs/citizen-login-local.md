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
