# Backend

API y procesos asíncronos del Módulo 5 - Rentas.

El backend se desplegará en dos modos a partir del mismo código y artefacto:

- `api`: servicio HTTP detrás de Application Load Balancer.
- `worker`: consumidor de eventos y ejecutor de procesos masivos.

Cuando se seleccione la tecnología se agregarán el proyecto base, pruebas,
migraciones de base de datos y un `Dockerfile` multi-stage.
