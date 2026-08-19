# Módulo ecs

Crea la plataforma mínima de contenedores:

- repositorio ECR cifrado, con etiquetas inmutables y escaneo al publicar;
- política que conserva las últimas imágenes;
- cluster ECS sin tareas ni servicios;
- grupo de logs de CloudWatch con retención limitada.

El servicio Fargate, las task definitions, los roles de ejecución y el ALB se
agregarán cuando exista el backend y su Dockerfile.
