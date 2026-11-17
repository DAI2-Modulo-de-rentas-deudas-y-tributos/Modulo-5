# Módulo network

Crea la red base del ambiente:

- una VPC con DNS habilitado;
- dos subredes públicas (ALB y NAT Gateway) en zonas de disponibilidad diferentes;
- dos subredes privadas `app` para las tareas Fargate, sin IP pública;
- dos subredes aisladas para PostgreSQL, sin ruta a Internet;
- Internet Gateway para las subredes públicas;
- un NAT Gateway con Elastic IP en la primera zona, salida única de las subredes `app`.

La Elastic IP (`nat_public_ip`) es la IP de salida fija del backend. Core la
autoriza como `/32` para suscripciones y envío de eventos, y no cambia con los
deploys ni con el reemplazo de tareas. Sólo cambia si se destruye la red del
ambiente (DEV lo hace en cada ejecución; TEST y PROD no).

Costo: el NAT Gateway se cobra por hora aunque ECS esté apagado (unos
US$ 33/mes por ambiente en us-east-2, más el tráfico procesado).
