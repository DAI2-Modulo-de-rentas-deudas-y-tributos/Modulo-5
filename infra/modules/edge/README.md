# Modulo edge

Publica la API mediante un Application Load Balancer y una distribucion
CloudFront. CloudFront entrega HTTPS al cliente con su dominio administrado, así que la
API no necesita un certificado en `us-east-1`. El cache de la API esta deshabilitado y se
reenvian todos los metodos HTTP.

CloudFront agrega a cada request el header `X-Origin-Verify` con un secreto generado
por Terraform; el ALB sólo reenvía al backend los requests que lo traen y responde 403
al resto, así que llamar al DNS del ALB o apuntarle otra distribución no llega a la API.
Las respuestas salen con la política administrada `Managed-SecurityHeadersPolicy` (HSTS,
`nosniff`, `X-Frame-Options`, `Referrer-Policy`). El ALB agrega la IP de CloudFront a
`X-Forwarded-For` sin el puerto.

## Origen HTTPS

`https_origin` es obligatorio: CloudFront llega al ALB sólo por HTTPS (`https-only`) y el
ALB no tiene listener HTTP, así que el secreto de `X-Origin-Verify` nunca viaja en texto
plano. El módulo busca el certificado ACM ya emitido para `https_origin.domain`
(`origin-<ambiente>.modulo-rentas.com.ar`, en la región del ALB), abre el listener
HTTPS:443 y publica el registro A del origen en `https_origin.zone`. El certificado y los
permisos de cada rol los administra `infra/bootstrap` ([ADR 0010](../../../docs/adr/0010-dominio-propio.md)).
El módulo `security` de la misma composición tiene que usar `alb_ingress_port = 443`.
