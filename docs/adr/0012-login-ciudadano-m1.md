# ADR 0012: Ingreso de vecinos con la cuenta de Ciudadanos (M1)

- Estado: aceptado (enmendado el 2026-11-25: alta en el primer ingreso)
- Fecha: 2026-11-23

## Contexto

El único borde de identidad de M5 era el login DEMO (`X-Demo-Session`), que el perfil `prod`
excluye. Por eso PROD respondía, pero nadie podía iniciar sesión (ADR 0009).

M1 (Ciudadanos) es dueño de las cuentas de los vecinos. Su login `POST /auth/usuario/login`
responde `auth`, `actorType` y `citizenId`: el UUID canónico que M5 ya guarda en
`taxpayer_reference.canonical_citizen_id` a partir de los eventos de M1. El JWT de M1 es HS256
con un secreto compartido (`APP_JWT_SECRET`). M8 (Desarrollo Social) ya integró ese login
reenviando las credenciales desde su backend y emitiendo una sesión propia.

## Decisión

- M5 hace lo mismo que M8. `POST /api/v1/auth/m1/login` recibe `email` y `password`, el backend
  llama a M1 y acepta la identidad sólo si `auth=true`, `actorType=CIUDADANO` y `citizenId` es
  un UUID coherente con `actorId`/`uuid`. El JWT de M1 se descarta y nunca se valida su firma.
- La identidad se resuelve por `canonical_citizen_id` hacia una referencia `CITIZEN` en estado
  `ACTIVE`/`ACTIVA`. Si no existe, M5 la crea en ese primer ingreso con los datos que devolvió
  el login de M1: UUID, DNI, CUIL, nombre y apellido, los mismos que guarda al recibir
  `citizenUpdated/REGISTERED`. El estado es `ACTIVE`, porque M1 sólo autentica a ciudadanos
  `ACTIVO`. El alta queda auditada como `TAXPAYER_REGISTERED_FROM_M1_LOGIN`. No la crea si M1
  no manda esos datos con el formato del evento (por ejemplo `dni=0`) ni si ya hay otra
  referencia de ciudadano con ese DNI, CUIL o ID externo: entonces responde
  `403 M1_CITIZEN_NOT_LINKED` y esa referencia se vincula a mano con `canonical-citizen-link`.
- M5 emite una sesión opaca propia (`citizen_session`: hash SHA-256 del token, vencimiento y
  revocación, V36) con rol `TAXPAYER` y el `taxpayerId` local. Viaja como
  `Authorization: Bearer`, vale en todos los perfiles y no forma parte del esquema DEMO.
  Cada pedido vuelve a leer la referencia: un `citizenBlocked`/`citizenDeceased` corta las
  sesiones abiertas.
- M5 reenvía a M1 la IP del vecino en `X-Forwarded-For`. M1 limita los intentos fallidos por
  esa IP; sin el encabezado, todos los vecinos compartirían la IP de salida del NAT (ADR 0011)
  y unos pocos errores bloquearían el ingreso de todos.
- Configuración por entorno: `M1_AUTH_ENABLED`, `M1_AUTH_BASE_URL` (origen HTTPS sin ruta; HTTP
  sólo en loopback), `M1_AUTH_CONNECT_TIMEOUT`, `M1_AUTH_READ_TIMEOUT` y `M1_AUTH_SESSION_TTL`.
  TEST y PROD lo activan. En el frontend, `VITE_AUTH_MODE=m1` (PROD) ofrece sólo este ingreso
  y `demo` lo ofrece junto con la cuenta DEMO de Rentas.
- El frontend reintenta hasta tres veces ante `M1_UNAVAILABLE`/`M1_TIMEOUT`, porque el plan
  de Render de M1 se suspende y el primer ingreso puede encontrarlo arrancando. M6 midió
  hasta 78 s de arranque: con `M1_AUTH_READ_TIMEOUT=25s` los tres intentos cubren unos 80 s
  y cada uno queda por debajo de los 30 s con que CloudFront corta la respuesta del origen.

## Alternativas

- Validar el JWT de M1 en M5 con el secreto HS256: obliga a compartir una clave con la que
  se pueden firmar tokens de cualquier vecino, a que M1 habilite el origen de M5 en CORS y a
  renovar el token contra M1 cada 15 minutos. Además, los tokens de M1 no se pueden revocar.
  Descartado.
- Esperar Core/JWT: no hay contrato ni fecha, y PROD sigue sin usuarios. Descartado.
- Crear la referencia sólo a partir de los eventos de M1: fue la decisión original (M1 es dueño
  del dato). Se enmendó el 25/11 porque a `q.rentas` no llega ningún `citizenUpdated` de M1 y
  ningún vecino podía entrar. Los datos siguen viniendo de M1, por su login en lugar de por un
  evento, y el `REGISTERED` que llegue después actualiza la misma referencia sin duplicarla.

## Consecuencias

- La contraseña del vecino pasa por el backend de M5. No se registra ni se guarda, y los
  `toString` de los DTO la ocultan.
- El ingreso depende de que M1 responda. Si M1 no está disponible, el ingreso falla con 503 o
  504, y las sesiones ya abiertas siguen funcionando.
- El ingreso no depende de que lleguen los eventos de M1. Sí se necesitan para que M5 se
  entere de bloqueos, fallecimientos y cambios de datos: sin ellos, una sesión ya abierta dura
  hasta su vencimiento (8 h), aunque M1 no deja abrir sesiones nuevas a una cuenta bloqueada.
- El personal de Rentas sigue sin ingreso en PROD: los roles de Rentas no existen en M1.
  Necesita su propia decisión (por ejemplo, cuentas locales como las de M8).
