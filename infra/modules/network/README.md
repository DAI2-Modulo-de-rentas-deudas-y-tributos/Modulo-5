# Módulo network

Crea la red base del ambiente:

- una VPC con DNS habilitado;
- dos subredes públicas en zonas de disponibilidad diferentes;
- dos subredes aisladas para la futura base PostgreSQL;
- Internet Gateway y ruta de salida únicamente para las subredes públicas.

No crea NAT Gateway. Mientras DEV no ejecute tareas Fargate, esto evita un
costo fijo innecesario. La estrategia de salida de las tareas se definirá junto
con el servicio ECS.
