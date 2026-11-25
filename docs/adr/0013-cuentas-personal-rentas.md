# ADR 0013: Cuentas propias del personal de Rentas

- Estado: aceptado
- Fecha: 2026-11-25

## Contexto

PROD no tiene ingreso para el personal de Rentas. El perfil `prod` excluye el esquema DEMO
(`V18__remove_demo_auth_schema.sql`, `DemoAuthService` con `@Profile("!prod")`, ADR 0009), y
los roles de Rentas no existen en M1, que sólo autentica vecinos para M5 (ADR 0012). Por eso
en PROD nadie podía supervisar, cobrar ni auditar.

## Decisión

- M5 tiene cuentas propias del personal en `staff_account` (V37): usuario, hash BCrypt, nombre,
  rol (`RENTAS`, `SUPERVISOR`, `CASHIER` o `AUDITOR`) y un UUID de actor para atribuir eventos.
  `SUPERVISOR` incluye además `RENTAS`, igual que en DEMO. Valen en todos los perfiles.
- Las cuentas se cargan desde `STAFF_ACCOUNTS`, un JSON con hashes BCrypt (costo 10 a 14) que en
  PROD viene de Secrets Manager. Al arrancar, M5 crea o actualiza las cuentas de la lista y deja
  inactivas las demás. Si cambia el hash de una cuenta, se revocan sus sesiones. Una
  configuración inválida corta el arranque y ECS revierte el despliegue.
- `POST /api/v1/auth/staff/login` emite una sesión opaca (`staff_session`: hash SHA-256 del
  token, vencimiento a las 8 h y revocación) que viaja como `Authorization: Bearer`, el mismo
  filtro que la sesión ciudadana. `GET /me` y `POST /logout` completan el ciclo.
- Usuario inexistente, contraseña incorrecta y cuenta inactiva responden igual (`401
  INVALID_CREDENTIALS`), y siempre se compara un hash BCrypt. Cinco fallos por usuario, o veinte
  por origen, bloquean el ingreso 15 minutos (`429 STAFF_LOGIN_RATE_LIMITED`). Altas, bajas,
  ingresos, fallos y bloqueos quedan en la auditoría.
- En el frontend, el modo `m1` (PROD) ofrece las dos cuentas: "Cuenta de Ciudadanos" para los
  vecinos y "Cuenta de Rentas" para el personal.

## Alternativas

- Que el personal entre con su cuenta de empleado de M1 y M5 sólo asigne el rol: no guarda
  contraseñas, pero exige cuentas de empleado en M1 para cada persona de Rentas. Descartado por
  decisión del PO.
- Encender el modo DEMO en PROD: contradice el ADR 0009 y expone el bootstrap y las cuentas de
  prueba. Descartado.
- Altas y cambios de contraseña desde el portal: más código y más superficie de ataque para un
  equipo chico de usuarios. Queda para cuando haga falta.

## Consecuencias

- M5 guarda hashes de contraseñas. Las contraseñas no pasan por el repositorio ni por la base:
  el hash se genera en la computadora de quien administra y se carga en Secrets Manager.
- Dar de alta, dar de baja o cambiar una contraseña es editar el secreto y reiniciar la tarea.
- El bloqueo por intentos vive en la memoria de la tarea. Alcanza con la tarea única de PROD; si
  el servicio escala a más de una, hay que pasarlo a PostgreSQL.
