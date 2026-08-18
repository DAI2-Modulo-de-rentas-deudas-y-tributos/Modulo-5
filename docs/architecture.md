# Arquitectura inicial

El módulo se implementará como un monolito modular desplegado en dos modos:

- API síncrona detrás de Application Load Balancer.
- Worker asíncrono para eventos, documentos y procesos masivos.

PostgreSQL será la fuente de verdad transaccional. Los archivos binarios se
guardarán en S3 y los eventos se integrarán mediante el broker definido por el
Core. La publicación confiable utilizará transactional outbox y los consumidores
mantendrán idempotencia mediante un registro inbox.

Los ambientes DEV y TEST promoverán el mismo artefacto inmutable.
