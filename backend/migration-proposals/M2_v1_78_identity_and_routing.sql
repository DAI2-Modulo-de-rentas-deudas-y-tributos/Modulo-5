-- SCRIPT DEFINITIVO PREPARADO, NO EJECUTAR TODAVÍA.
-- Permanece fuera de db/migration: Flyway no lo descubre ni lo ejecuta.
-- Al autorizar el despliegue, moverlo como la siguiente migración versionada
-- y aplicar código + esquema juntos, después de un respaldo y una revisión
-- de los recuentos de usuarios/tickets. Requiere PostgreSQL 17 (compose.yaml).
-- La ruta Flyway común también se usa en prod, donde V18 elimina demo_user:
-- por eso la parte DEMO se aplica solamente si esa tabla todavía existe.

-- No se modifica demo_user.id (BIGINT). Cada usuario previo recibe exactamente
-- un UUID nuevo y persistido; posteriores reinicios no vuelven a generarlo.
DO $migration$
BEGIN
    IF to_regclass('public.demo_user') IS NOT NULL THEN
        ALTER TABLE demo_user ADD COLUMN integration_actor_id UUID;
        UPDATE demo_user
           SET integration_actor_id = gen_random_uuid()
         WHERE integration_actor_id IS NULL;
        ALTER TABLE demo_user ALTER COLUMN integration_actor_id SET DEFAULT gen_random_uuid();
        ALTER TABLE demo_user ALTER COLUMN integration_actor_id SET NOT NULL;
        ALTER TABLE demo_user ADD CONSTRAINT uq_demo_user_integration_actor_id UNIQUE (integration_actor_id);
    END IF;
END
$migration$;

-- No se modifica ticket_case.id (BIGINT), external_ticket_id ni ningún dato
-- legado. Las columnas son anulables porque ciertos tickets históricos no
-- poseen un snapshot ROUTED completo recuperable.
ALTER TABLE ticket_case ADD COLUMN request_type VARCHAR(255);
ALTER TABLE ticket_case ADD COLUMN subcategory VARCHAR(255);

-- Recuperación conservadora desde el último ROUTED oficial procesado.
-- Sólo se corrige category cuando conserva el requestType que guardaba el
-- adaptador anterior, o el marcador de ausencia M2. Cualquier otro valor
-- existente se preserva. Los tickets legados source_module=M2 no se tocan.
WITH routed_events AS (
    SELECT log.id,
           CASE WHEN pg_input_is_valid(log.payload, 'jsonb')
                THEN log.payload::jsonb END AS event_data
      FROM integration_event_log AS log
     WHERE log.direction = 'INBOUND'
       AND log.status = 'PROCESSED'
       AND log.event_type = 'ticketUpdated'
       AND log.source_module = 'atencion-ciudadana'
), latest_routing AS (
    SELECT DISTINCT ON (event_data #>> '{data,ticketId}')
           event_data #>> '{data,ticketId}' AS ticket_id,
           NULLIF(event_data #>> '{data,details,routing,requestType}', '') AS request_type,
           NULLIF(event_data #>> '{data,details,routing,category}', '') AS category,
           NULLIF(event_data #>> '{data,details,routing,subcategory}', '') AS subcategory
      FROM routed_events
     WHERE event_data #>> '{data,updateType}' = 'ROUTED'
     ORDER BY event_data #>> '{data,ticketId}',
              NULLIF(event_data #>> '{data,updatedAt}', '')::timestamptz DESC NULLS LAST,
              id DESC
)
UPDATE ticket_case AS ticket
   SET request_type = COALESCE(latest.request_type, ticket.request_type),
       subcategory = COALESCE(latest.subcategory, ticket.subcategory),
       category = CASE
           WHEN latest.category IS NOT NULL
                AND (ticket.category = latest.request_type OR ticket.category = 'M2')
           THEN latest.category
           ELSE ticket.category
       END
  FROM latest_routing AS latest
 WHERE ticket.external_ticket_id = latest.ticket_id;
