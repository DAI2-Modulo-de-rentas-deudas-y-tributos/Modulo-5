# M5–M8, etapa 1: estado de migraciones y condición de despliegue

**Estado verificado en la rama de integración:** V24, V25 y V26 están en `src/main/resources/db/migration-postgresql`, y `application.yml` y `application-prod.yml` incluyen esa ubicación con Flyway habilitado. Por lo tanto, el arranque del backend intenta aplicar automáticamente las migraciones pendientes. Esto contradice los comentarios `PROPOSAL ONLY` de V25/V26 y el comentario de prueba de V24. Las copias de SQL en esta carpeta son sólo documentación; no cambian ni desactivan la ubicación activa.

No iniciar el backend contra una base compartida ni cambiar unilateralmente la política de Flyway. La aprobación explícita del PO sobre la autoaplicación de V24–V26 es condición para merge y despliegue. No se ejecutó SQL en la base real `rentas`.

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

La prueba automatizada `M5OwnedUuidMigrationPostgreSqlTest` valida desde V23 con filas históricas representativas el upgrade Flyway a V26 en las ubicaciones comunes y en el conjunto de producción (V18-production + V24–V26): IDs UUID no nulos, únicos y estables; PK Long sin cambios; y `canonical_citizen_id` sin datos fabricados. `M8ProductionPostgreSqlMigrationTest` comprueba además el perfil prod, las migraciones V18/V24–V26 y `Hibernate ddl-auto=validate` en PostgreSQL 17. Estas pruebas efímeras no sustituyen un ensayo sobre una copia restaurada de datos existentes ni constituyen aprobación productiva.

Antes de desplegar, con autorización del PO: obtener un respaldo nuevo; restaurar una copia aislada representativa en V23; validar conteos y relaciones antes/después, UUID existentes, `Hibernate ddl-auto=validate` y el perfil de producción sin `demo_user`; conservar el respaldo y el resultado del ensayo para rollback. No editar migraciones aplicadas ni ejecutar SQL manualmente sobre `rentas`. El payload compartido `debtSettled` y el transporte real con Core siguen sujetos a sus acuerdos externos.
