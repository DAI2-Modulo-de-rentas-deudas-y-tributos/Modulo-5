# Rollback de la migración Flyway V32 (vencimientos durables)

Cubre qué hacer si la migración
[`V32__durable_due_date_processing_jobs.sql`](../../backend/src/main/resources/db/migration/V32__durable_due_date_processing_jobs.sql)
(SCRUM-792) o el código que la usa causan un problema en PROD.

## Qué cambia V32

Sólo toca la tabla `due_date_processing`:

- quita el `NOT NULL` de `processed_at` (un job encolado todavía no tiene fecha de proceso);
- agrega 10 columnas con valor por defecto: `status`, `requested_at`, `started_at`,
  `completed_at`, `lease_expires_at`, `next_attempt_at`, `attempt_count`, `last_error_code`,
  `processing_stage`, `last_processed_id`;
- agrega los CHECK `ck_due_date_processing_status` y `ck_due_date_processing_stage` y el índice
  `idx_due_date_processing_queue`.

No borra ni reescribe datos: las filas previas quedan `COMPLETED`. Desde V32,
`POST /api/v1/administration/process-due-dates` sólo **encola** la fecha y el worker
(`DueDateProcessingWorker`, cada 5 s dentro del mismo backend) la procesa por lotes, con hasta
3 intentos antes de dejarla `FAILED`.

## Estado en PROD

- V32 llegó a PROD en el **primer y único deploy** de `cd-prod.yml`: commit `139ff7e`
  (PR #152), 2026-11-15 02:30 UTC. PROD nunca corrió sin V32.
- Por eso **no existe en PROD una imagen ni una revisión de task definition anterior a V32**
  a la cual volver con un clic: "volver a V31" exige construir y desplegar una imagen nueva.
- Respaldo: backups automáticos de RDS con 7 días de retención (restauración a un instante),
  `deletion_protection = true` y snapshot final al destruir (`infra/environments/prod`).
  `cd-prod.yml` **no** saca un snapshot manual antes de migrar.
- Una restauración a un instante anterior a 2026-11-15 02:30 UTC no devuelve "PROD con V31":
  devuelve la base tal como estaba antes de ese primer deploy (verificar `InstanceCreateTime`).

Nombres en PROD (`us-east-2`):

| Recurso | Nombre |
|---|---|
| RDS | `modulo-5-rentas-prod-postgres` |
| Cluster / servicio ECS | `modulo-5-rentas-prod-cluster` / `modulo-5-rentas-prod-backend` |
| Familia de task definition | `modulo-5-rentas-prod-backend` |
| Repositorio ECR | `modulo-5-rentas-prod-backend` (tag = SHA del commit) |

## Elegir la salida

De la menos a la más invasiva. Avanzar sólo si la anterior no alcanza.

1. **Corregir hacia adelante** (preferida): fix en el código y, si hace falta tocar el esquema,
   una migración `V33__...`. Nunca editar V32: Flyway valida su checksum en cada arranque.
2. **Volver sólo el código** y dejar el esquema con V32 (sección A). V32 es compatible hacia
   atrás con el código anterior.
3. **Revertir también el esquema** (sección B). Sólo si el esquema mismo es el problema.
4. **Restaurar RDS a un instante** (sección C). Último recurso: pierde todo lo posterior.

## Antes de cualquier salida

1. Snapshot manual (cuesta minutos y da un punto exacto de vuelta):

   ```bash
   aws rds create-db-snapshot --region us-east-2 \
     --db-instance-identifier modulo-5-rentas-prod-postgres \
     --db-snapshot-identifier m5-prod-pre-rollback-v32-$(date -u +%Y%m%d%H%M)
   aws rds wait db-snapshot-available --region us-east-2 \
     --db-snapshot-identifier m5-prod-pre-rollback-v32-<sufijo>
   ```

2. Revisar el estado de Flyway y de los jobs:

   ```sql
   SELECT installed_rank, version, description, success, installed_on
   FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;

   SELECT id, processing_date, status, processing_stage, attempt_count, last_error_code
   FROM due_date_processing WHERE status <> 'COMPLETED' ORDER BY processing_date;
   ```

3. **No volver atrás con jobs sin terminar.** El código anterior a V32 trata cualquier fila de
   `due_date_processing` como "fecha ya procesada" (`findByProcessingDate`), aunque esté
   `PENDING`, `PROCESSING` o `FAILED`: esa fecha no se procesaría nunca.
   - `PENDING` / `PROCESSING`: esperar a que el worker las deje `COMPLETED`.
   - `FAILED`: volver a encolar con
     `POST /api/v1/administration/process-due-dates` y `{"processingDate": "AAAA-MM-DD"}`
     (rol `RENTAS` o `SUPERVISOR`; resetea los intentos) y esperar `COMPLETED`.
   - Si una fecha no termina nunca, recién entonces borrar su fila para que la versión anterior
     la procese de nuevo desde cero. Hacerlo con el snapshot del paso 1 tomado.

## A. Volver sólo el código (esquema queda en V32)

1. Revertir en un PR el código Java de SCRUM-792 **sin borrar ni editar V32** y llevarlo a
   `main`; el merge dispara `cd-prod.yml`. Con el archivo presente y su checksum intacto, Flyway
   no tiene nada que aplicar y Hibernate (`ddl-auto: validate`) acepta las columnas de más.
2. Alternativa si el PR no es viable: construir la imagen de un commit anterior a V32 (el
   último es `8c39d7c`; no está en el ECR de PROD), publicarla con su SHA como tag, registrarla
   como nueva revisión de la familia de task definition y apuntar el servicio a ella. Flyway 11 (el backend usa 11.7.2) arranca igual: deja en el log
   `Schema "public" has a version (32) that is newer than the latest available migration (31) !`
   como WARN, no como error. Es esperado.
3. Verificar: `/actuator/health` en `UP`, `rolloutState = COMPLETED` en ECS, y que las corridas
   nuevas queden con `status = 'COMPLETED'`: el código anterior no conoce esas columnas y las
   completa el valor por defecto.

## B. Revertir el esquema

Requiere que ya esté desplegado un código que **no** use las columnas de V32 (sección A).
Ejecutar como el usuario dueño del esquema; aborta solo si quedan jobs sin terminar:

```sql
BEGIN;

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM due_date_processing WHERE status <> 'COMPLETED') THEN
    RAISE EXCEPTION 'Hay jobs de vencimientos sin terminar: resolverlos antes de revertir V32';
  END IF;
END $$;

-- Los CHECK y el índice de V32 caen junto con sus columnas.
ALTER TABLE due_date_processing
  DROP COLUMN status,
  DROP COLUMN requested_at,
  DROP COLUMN started_at,
  DROP COLUMN completed_at,
  DROP COLUMN lease_expires_at,
  DROP COLUMN next_attempt_at,
  DROP COLUMN attempt_count,
  DROP COLUMN last_error_code,
  DROP COLUMN processing_stage,
  DROP COLUMN last_processed_id;

-- Toda fila COMPLETED tiene processed_at.
ALTER TABLE due_date_processing ALTER COLUMN processed_at SET NOT NULL;

-- Flyway vuelve a ver V31 como última versión aplicada.
DELETE FROM flyway_schema_history WHERE version = '32';

COMMIT;
```

Si el bloque `DO` aborta, la transacción no cambia nada: resolver los jobs (ver "Antes de
cualquier salida") y volver a correrlo.

Después, reiniciar el servicio y confirmar en el log
`Current version of schema "public": 31` y `Schema "public" is up to date`, ya sin el WARN
de la sección A. Si más adelante se vuelve a desplegar código con V32, Flyway la aplica de
nuevo sobre las filas existentes sin intervención (quedan `COMPLETED`).

## C. Restaurar RDS a un instante

Crea una **instancia nueva**; no pisa la actual. Todo lo registrado después del instante elegido
(pagos, deudas, eventos del outbox/inbox) se pierde y hay que reconciliarlo con M4, M7 y M8.

```bash
aws rds restore-db-instance-to-point-in-time --region us-east-2 \
  --source-db-instance-identifier modulo-5-rentas-prod-postgres \
  --target-db-instance-identifier modulo-5-rentas-prod-postgres-restore \
  --restore-time 2026-11-15T02:29:00Z
```

Luego hay que llevar la instancia restaurada al estado de Terraform (identificador, subred,
security group, secreto): planificarlo y revisar el `terraform plan` antes de aplicar.

## Prueba local del procedimiento (2026-11-15)

Sobre PostgreSQL 17 (`postgres:17-alpine`, el de `compose.yaml`) en un contenedor aislado, con
los JAR de `8c39d7c` (hasta V31) y `139ff7e` (el de PROD), perfil `prod` y Java 17
(`eclipse-temurin:17-jre-jammy`):

| Paso | Resultado |
|---|---|
| JAR V31 sobre base vacía | aplica V1–V31, health `UP` |
| 2 corridas al estilo V31 → JAR de PROD | aplica V32, las filas quedan `COMPLETED`, `UP` |
| 1 job `PENDING` + 1 `FAILED` → JAR V31 sobre esquema V32 (sección A) | WARN "newer than the latest available migration", `UP` |
| Insert al estilo V31 sobre esquema V32 | `status`/`processing_stage` = `COMPLETED` por defecto |
| SQL de la sección B con jobs sin terminar | aborta con la excepción, esquema intacto |
| SQL de la sección B ya sin esos jobs | `COMMIT`; DDL de `due_date_processing` idéntica (`pg_dump`) a la de una base V31 nueva |
| JAR V31 sobre la base revertida | versión 31, "up to date", sin WARN, `UP` |
| JAR de PROD de nuevo | vuelve a aplicar V32, `UP` |

No probado: la sección C (necesita AWS) y el reproceso de una fecha cuya fila se borró, con
deudas reales. Ese reproceso depende de que el outbox de `overdueDebt` (V22) y los recargos
ya aplicados no se dupliquen.

## Pendiente

- Agregar a `cd-prod.yml` un snapshot manual de RDS antes de desplegar el backend, para que
  cada migración tenga un punto de vuelta exacto.
