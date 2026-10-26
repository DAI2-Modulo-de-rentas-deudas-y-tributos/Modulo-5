# M5–M8, etapa 1: propuestas pendientes de aprobación

Los archivos `V25__m8_owned_integration_ids.sql` y `V26__m8_social_benefits_and_rules.sql` no están en las ubicaciones activas de Flyway. Su presencia en esta carpeta **no los ejecuta**. No iniciar el backend con el nuevo mapeo JPA sobre una base anterior a V26 hasta haber aprobado, ensayado en una copia PostgreSQL y aplicado ambas migraciones.

## V25 — identificadores propios de M5

- Agrega `integration_id UUID NOT NULL` y restricción `UNIQUE` a `debt`, `liquidation`, `exemption_request` y `exemption`; conserva sus PK `BIGINT`.
- PostgreSQL asigna `gen_random_uuid()` una vez a cada fila histórica y como valor por defecto a las nuevas. El backend también asigna el UUID antes de insertar. No se regeneran al actualizar.
- Agrega `taxpayer_reference.canonical_citizen_id UUID UNIQUE` nullable, sin backfill. Sólo podrá completarse con una correspondencia de M1 verificada; no equivale al `external_id` numérico actual.

## V26 — beneficios, reglas y procedencia

- Permite `social_benefit_reference.valid_from` nulo, como admite el contrato M8.
- Añade motivo, estado de vinculación, hash del payload, correlación y recepción al beneficio. Las filas previas reciben valores de compatibilidad `LEGACY` y un hash marcador, sin alterar beneficio, porcentaje ni vigencia anteriores.
- Crea `social_benefit_event_history` para registrar eventos aplicados, antiguos y conflictivos.
- Crea `social_benefit_rule` con programa UUID de M8, tributo de M5, porcentaje, vigencia, estado, habilitación, versión y responsable aprobador. No se infiere una regla del nombre del programa.
- Añade a `liquidation_component` el beneficio externo, regla y versión utilizados por una nueva liquidación. Los componentes históricos quedan con esos campos nulos.

Antes de moverlos a Flyway: obtener un respaldo nuevo, inspeccionar el esquema y las cantidades reales, probar V25 y V26 en una base aislada restaurada, verificar los UUID históricos y la compatibilidad con el perfil productivo sin `demo_user`, y ejecutar las suites. La base original `rentas` no se modifica en esta etapa. El payload compartido `debtSettled` y el transporte real con Core quedan para acuerdos y validaciones posteriores.
