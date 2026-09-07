# Reporte final de integración full-stack M5

## Alcance validado

La rama `feature/fullstack-real-integration`, creada desde `develop` en `9f8f52408748e016e59b2cf2cad4810d3b1a9184`, conecta frontend Vite, backend Spring Boot y PostgreSQL 17 real. Con `VITE_USE_MOCKS=false`, todos los writes de dominio usan HTTP y persistencia backend; los errores HTTP se propagan y no activan un fallback a fixtures o `mockDb`.

## Entorno real

| Componente | Evidencia |
|---|---|
| Docker | Docker Desktop 29.7.2 operativo |
| PostgreSQL | `postgres:17-alpine`, servidor 17.11, Compose aislado `m5fullstack` |
| Flyway | base vacía migrada exitosamente de V1 a V14 |
| Hibernate | `ddl-auto=validate`, modelo JPA validado contra PostgreSQL |
| Backend | Actuator `200 UP`; `/api/v1/health` 200 |
| Frontend | Vite en modo API real y proxy hacia Spring Boot |
| OpenAPI | 132 paths, 147 operaciones, cero `/api/v1/events*` públicos |

## Autenticación DEMO/DEV persistente

- `demo_user` persiste username, hash BCrypt, rol, vínculo opcional a taxpayer y estado.
- El bootstrap local sólo corre cuando dev mode está habilitado y `RENTAS_DEMO_BOOTSTRAP_PASSWORD` no está vacío.
- `integration.taxpayer` fue creado mediante el backend, comprobado por SQL y usado para iniciar sesión desde el frontend.
- Los cinco roles se autenticaron correctamente; contraseña incorrecta devolvió 401 y logout eliminó la sesión local.
- La identidad/rol de las requests posteriores proviene de la respuesta del backend. El acceso del contribuyente a otro taxpayer devolvió 403.
- Con `rentas.security.dev-mode=false`, los controladores dev-auth y simulación M2 no se registran.

## Pruebas UI → API → PostgreSQL

Desde formularios frontend reales se crearon y verificaron por HTTP/SQL:

- configuración tributaria propuesta, enviada y aprobada;
- liquidación individual y deuda asociada;
- boleta;
- pago con imputación y saldo de deuda actualizado;
- solicitud de plan;
- solicitud de exención usando conceptos obtenidos por API;
- preview/aplicación de recargos, procesamiento de vencimientos y conciliación electrónica.

Después de reiniciar el backend sin borrar PostgreSQL, las filas continuaron en la base y volvieron a verse por API/portal. Resultado: `PERSISTENCE_RESTART_VALIDATED`.

## Procesamiento fiscal nuevo

### Recargos e intereses

El backend calcula con `BigDecimal` días vencidos, principal, tasas, recargo, interés, ajustes previos y total. Preview no escribe. La confirmación es transaccional, auditada e idempotente por deuda/fecha/regla. Se corrigió además el cotejo temporal de conciliaciones para comparar fechas en `America/Argentina/Buenos_Aires`, evitando el cambio de día causado por `timestamptz`/UTC después de las 21:00.

### Vencimientos

La acción administrativa procesa deudas y cuotas vencidas, genera los ajustes pendientes, identifica planes incumplidos y crea la solicitud de caducidad según su configuración. La ejecución queda auditada y una repetición para la misma fecha no duplica efectos.

### Conciliación electrónica

Los lotes e ítems se persisten con referencias únicas. El matching produce `CONCILIATED`, `OBSERVED` o `NOT_FOUND`; los observados pueden resolverse manualmente con control de pertenencia y auditoría. La importación repetida del mismo lote es idempotente.

## Integridad económica y concurrencia

- Pago: `amount = allocatedAmount + unallocatedAmount`.
- Imputación de cuota: `principalApplied + interestApplied = amount`; sólo principal reduce deuda.
- Sobrepago: genera `CreditBalance` por el remanente.
- Reversión: restaura sólo principal, marca pago/imputación `REVERSED` y no duplica ejecución.
- Locks reales cubren pagos concurrentes, crédito, planes, reversión, M7 y outbox.
- El claim de outbox usa `FOR UPDATE SKIP LOCKED`; cada worker toma un evento una sola vez.
- Las consultas paginadas de liquidaciones, deudas y boletas, indicadores agregados y planes vencidos permanecen acotadas sin regresiones N+1.

## Migraciones

- V1–V12 permanecen intactas.
- V13 siembra idempotentemente `TASA_SERVICIOS`, `ABL` y `PATENTE`.
- V14 agrega autenticación demo y persistencia para reglas/aplicaciones de recargo, ejecuciones de vencimientos y conciliación.
- `flyway_schema_history` registró V1–V14 exitosas sobre un esquema vacío.

## M2, M4 y M7

- M2: `ticketCreated` y `ticketUpdated` consumen el envelope común, usan inbox/eventId, EventLog y DLQ. En dev se simulan por `POST /api/v1/dev/integrations/m2/events`; el procedimiento está en README.
- M4: las pruebas contractuales preliminares pasan sin efectos económicos. Se preserva `BLOCKED_M4_TAXPAYER_RESOLUTION` hasta recibir `establishmentId → taxpayer`.
- M7: source `transito`, resolución DNI/CUIT, `finalAmount`, idempotencia por evento/obligación, rollback y concurrencia pasan en PostgreSQL real.
- Core/JWT, broker y contratos outbound permanecen externos; no se inventaron.

## Seguridad

- Roles efectivos: `RENTAS`, `SUPERVISOR`, `CASHIER`, `AUDITOR`, `TAXPAYER`.
- AUDITOR puede leer y no escribir.
- TAXPAYER sólo accede a recursos propios.
- CASHIER conserva únicamente sus acciones; RENTAS puede registrar pagos desde la pantalla operativa.
- Errores centralizados: `status`, `code`, `message`, `traceId`, `details`.
- Flags dev tienen default `false`.

## Calidad

- Backend: `mvnw.cmd clean verify` terminó `BUILD SUCCESS`; 132 pruebas, 132 aprobadas, 0 failures, 0 errors y 0 skipped.
- PostgreSQL Testcontainers: 13/13 aprobadas contra `postgres:17-alpine` y PostgreSQL 17.11.
- JaCoCo: líneas 88,30% (951/1077), instrucciones 86,65% (19605/22625) y branches 56,95% (586/1029); gate de líneas ≥85% aprobado.
- Frontend: 24 archivos y 304 pruebas aprobadas; `npm run build` exitoso.
- El bundle conserva un warning no bloqueante por tamaño; no afecta la corrección funcional.

## Variables de entorno y secretos

- Agregada: `RENTAS_DEMO_BOOTSTRAP_PASSWORD`.
- Tipo: secreto opcional, sólo LOCAL/DEV; no tiene valor real ni default sensible.
- Uso: crear los cinco usuarios de bootstrap únicamente cuando `RENTAS_SECURITY_DEV_MODE=true`.
- Configuración: terminal/Compose/secret store local o de CI de integración; nunca producción ni archivos `.env` versionados.
- `.env.example`, `backend/.env.example` y README contienen sólo `CHANGE_ME`/placeholders seguros.
- No se agregaron otros secretos ni se mostraron credenciales locales.

## Entrega

Los commits, PR y checks remotos se registran en el cierre de la tarea. El PR se dirige a `develop`; no se realiza merge ni deploy.
