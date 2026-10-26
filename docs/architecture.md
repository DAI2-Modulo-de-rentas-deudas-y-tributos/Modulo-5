# Arquitectura inicial

El módulo se implementará como un monolito modular desplegado en dos modos:

- API síncrona detrás de Application Load Balancer.
- Worker asíncrono para eventos, documentos y procesos masivos.

PostgreSQL será la fuente de verdad transaccional. Los archivos binarios se
guardarán en S3 y los eventos se integrarán mediante el broker definido por el
Core. La publicación confiable utilizará transactional outbox y los consumidores
mantendrán idempotencia mediante un registro inbox.

## Consultas paginadas

Los endpoints paginados aceptan un máximo de 100 registros por página. El valor
mantiene compatibilidad con los clientes del proyecto, que ya solicitan páginas
de hasta 100 elementos. Los filtros se construyen con Criteria API parametrizada;
los campos filtrables y los campos de ordenamiento de las consultas genéricas se
validan mediante allowlists.

Los ambientes DEV y TEST promoverán el mismo artefacto inmutable.
