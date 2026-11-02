# Modulo hosting

Crea una aplicacion y una rama en Amplify Hosting sin conectarlas directamente
a GitHub. GitHub Actions compila el frontend y usa la API de despliegue manual
de Amplify mediante OIDC, evitando tokens personales y secretos permanentes.

Las cabeceras de seguridad (CSP con `frame-ancestors 'none'`, HSTS, `nosniff`,
`Referrer-Policy`) se configuran en `custom_headers` de la app: con despliegue manual
Amplify no lee `amplify.yml`, por eso no van ahí. La CSP admite la API
(`VITE_API_BASE_URL`) y Google Fonts; si el frontend suma otro origen, hay que agregarlo.

El modulo combina `VITE_API_BASE_URL` con `environment_variables`. Todas las
variables `VITE_*` son publicas porque Vite las incorpora al JavaScript del navegador;
los secretos pertenecen al backend y deben permanecer en Secrets Manager.
