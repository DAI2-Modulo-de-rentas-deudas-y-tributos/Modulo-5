# ADR 0010: Dominio propio `modulo-rentas.com.ar`

- Estado: aceptado
- Fecha: 2026-11-02 (registrado el 2026-11-16 según lo que quedó construido)

## Contexto

TEST y PROD se publicaban sólo con los dominios que asigna AWS (`*.cloudfront.net` para la
API, `*.amplifyapp.com` para el frontend). Además, el tramo CloudFront -> ALB iba por HTTP,
porque un certificado ACM para el ALB necesita un nombre propio. El equipo compró
`modulo-rentas.com.ar` en NIC Argentina.

## Decisión

- **DNS en Route 53, dominio en NIC.ar.** Route 53 no registra ni transfiere `.com.ar`: el
  dominio queda en NIC.ar, que delega el DNS en una zona pública de Route 53.
- **La zona vive en `infra/bootstrap`** con `prevent_destroy`. Recrearla cambia los
  nameservers y obliga a repetir la delegación a mano, así que no puede depender de un
  ambiente: DEV se destruye en cada merge y TEST puede destruirse desde su workflow.
- **Origen HTTPS de la API.** Cada ambiente tiene un certificado ACM
  `origin-<ambiente>.modulo-rentas.com.ar` en `us-east-2`, emitido y validado por DNS en el
  bootstrap. Con `https_origin`, el módulo `edge` busca ese certificado, abre el listener
  HTTPS:443 del ALB, publica el registro A `origin-<ambiente>` y CloudFront llega al ALB en
  `https-only`. El ALB sigue exigiendo `X-Origin-Verify`. `https_origin` es obligatorio: el
  módulo ya no tiene listener HTTP:80 ni origen `http-only` (cierra el hallazgo de
  SonarQube `terraform:S5332`).
- **Frontend.** `aws_amplify_domain_association` (variable `custom_domains` del módulo
  `hosting`). Amplify emite su certificado y el módulo crea en la zona el alias y el CNAME de
  verificación.
- **Nombres por ambiente:**

  | Ambiente | Frontend | API | Origen del ALB |
  |---|---|---|---|
  | TEST | `test.modulo-rentas.com.ar` | dominio de CloudFront | `origin-test.modulo-rentas.com.ar` |
  | PROD | dominio de Amplify | dominio de CloudFront | `origin-prod.modulo-rentas.com.ar` (PR #173) |
  | DEV | dominio de Amplify | dominio de CloudFront | `origin-dev.modulo-rentas.com.ar` (se crea y se destruye con el ambiente) |

- **CORS.** TEST acepta el dominio de Amplify y `https://test.modulo-rentas.com.ar`
  (`cors_allowed_origins` en `infra/environments/test`).
- **Permisos.**
  - `github-<ambiente>-domain` (TEST y PROD): leer la zona, certificados ACM y asociaciones de
    dominio de Amplify. TEST sólo puede escribir `test.modulo-rentas.com.ar` y sus
    subdominios; PROD, todos los demás nombres.
  - `api-origin-tls` (DEV, TEST y PROD): leer su propio certificado de origen y escribir sólo
    el registro A de su `origin-<ambiente>`. Una denegación explícita le impide tocar los
    orígenes de los otros ambientes, algo que la política de dominio de PROD admitiría.

## Alternativas

- Dejar el DNS en NIC.ar y cargar los registros a mano: NIC.ar no tiene API, los certificados
  ACM no se validarían solos y cada ambiente nuevo exigiría un paso manual. Descartado.
- Otro proveedor de DNS (p. ej. Cloudflare): suma una cuenta y un provider de Terraform más.
  Descartado.
- Un certificado comodín `*.modulo-rentas.com.ar`: todos los ambientes compartirían el mismo
  certificado y no se podría limitar a cada rol al suyo. Descartado.
- Emitir el certificado de origen dentro de cada ambiente: DEV se recrea en cada merge, así que
  cada ciclo pediría y validaría un certificado nuevo. Descartado.
- Dominio propio para la API en CloudFront (`api.<dominio>`): era la primera versión de esta
  decisión y no se implementó. Necesita un certificado en `us-east-1` (la única región que
  acepta CloudFront) y un provider más por ambiente. Queda para una decisión posterior.

## Consecuencias

- La delegación en NIC.ar es un paso manual que se hizo una vez
  (`infra/bootstrap/README.md`). Si se pierde la delegación, ACM y Amplify no pueden validar
  ni renovar certificados.
- La zona cuesta USD 0,50 por mes. Los certificados ACM públicos son gratuitos.
- La renovación de los certificados es automática mientras los registros de validación sigan
  en la zona; los administra el bootstrap.
- En un ambiente que todavía tenga el listener HTTP:80 (PROD hasta aplicar el PR #173), el
  primer `apply` lo reemplaza por el HTTPS:443 y cambia el origen de CloudFront a
  `origin-<ambiente>`. Mientras CloudFront propaga el cambio, la API puede devolver errores un
  rato.
- Un ambiente nuevo necesita su certificado `origin-<ambiente>` emitido en el bootstrap antes
  del primer `apply`: sin él, `edge` falla al buscarlo.
- La zona y los certificados se aplican a mano desde el bootstrap. Antes de cualquier `apply`,
  `terraform plan` tiene que coincidir con el estado: si el código queda atrás, el `apply`
  destruye la zona delegada y los certificados en uso.
- Pendiente: publicar el frontend de PROD en la raíz `modulo-rentas.com.ar`. La política
  `github-prod-domain` ya lo permite; falta la composición de PROD y su CORS.
