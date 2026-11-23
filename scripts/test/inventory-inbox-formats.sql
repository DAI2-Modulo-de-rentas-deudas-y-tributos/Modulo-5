\set ON_ERROR_STOP on
BEGIN READ ONLY;
SET LOCAL statement_timeout = '15s';

WITH pending AS MATERIALIZED (
    SELECT event_type, source_module, status,
        CASE WHEN pg_input_is_valid(payload, 'jsonb') THEN payload::jsonb END AS envelope
    FROM integration_event_log
    WHERE direction = 'INBOUND' AND status IN ('RECEIVED', 'PENDING', 'FAILED', 'DEAD_LETTER')
), classified AS (
    SELECT status, CASE
        WHEN envelope IS NULL OR jsonb_typeof(envelope) <> 'object' THEN 'INVALID_ENVELOPE'
        WHEN NOT envelope ? 'data' OR jsonb_typeof(envelope->'data') <> 'object' THEN 'UNCLASSIFIED'
        WHEN event_type IN ('taxpayerCreated', 'taxpayerUpdated', 'taxpayerSynchronized', 'ticketCreated') THEN 'LEGACY_TYPE'
        WHEN event_type IN ('permitFeeGenerated', 'commercialFineGenerated')
            THEN CASE WHEN envelope ? 'eventId' THEN 'CURRENT_ROUTE' ELSE 'LEGACY_ROUTE' END
        WHEN event_type = 'infractionConfirmed'
            THEN CASE WHEN envelope->'data' ? 'infractionId' THEN 'CURRENT_ROUTE' ELSE 'LEGACY_ROUTE' END
        WHEN event_type = 'ticketUpdated'
            THEN CASE WHEN source_module = 'atencion-ciudadana' THEN 'CURRENT_ROUTE' ELSE 'LEGACY_ROUTE' END
        WHEN event_type = 'socialBenefitUpdated'
            THEN CASE WHEN envelope->'data' ? 'benefitId' THEN 'CURRENT_ROUTE' ELSE 'LEGACY_ROUTE' END
        ELSE 'UNCLASSIFIED'
    END AS format_hint
    FROM pending
)
SELECT status, format_hint, count(*) AS records
FROM classified
GROUP BY status, format_hint
ORDER BY status, format_hint;

COMMIT;
