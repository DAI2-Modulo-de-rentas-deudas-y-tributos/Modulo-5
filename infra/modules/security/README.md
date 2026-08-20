# Modulo security

Implementa el aislamiento de red entre CloudFront, ALB, ECS Fargate y RDS.
El ALB acepta trafico solamente desde la lista administrada de origenes de
CloudFront; el backend acepta trafico solamente desde el ALB; PostgreSQL acepta
conexiones solamente desde el backend.
