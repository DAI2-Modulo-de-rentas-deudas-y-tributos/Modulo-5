# Modulo edge

Publica la API mediante un Application Load Balancer y una distribucion
CloudFront. CloudFront entrega HTTPS con su dominio administrado, por lo que DEV
no necesita comprar ni configurar un dominio. El cache de la API esta
deshabilitado y se reenvian todos los metodos HTTP.

CloudFront agrega a cada request el header `X-Origin-Verify` con un secreto generado
por Terraform; el ALB sólo reenvía al backend los requests que lo traen y responde 403
al resto, así que llamar al DNS del ALB o apuntarle otra distribución no llega a la API.
Las respuestas salen con la política administrada `Managed-SecurityHeadersPolicy` (HSTS,
`nosniff`, `X-Frame-Options`, `Referrer-Policy`). El tramo CloudFront -> ALB sigue en
HTTP: cifrarlo requiere un dominio propio con certificado ACM en el ALB.
