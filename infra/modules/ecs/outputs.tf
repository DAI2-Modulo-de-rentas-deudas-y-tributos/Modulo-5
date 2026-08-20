output "cluster_arn" {
  description = "ARN del cluster ECS."
  value       = aws_ecs_cluster.this.arn
}

output "cluster_name" {
  description = "Nombre del cluster ECS."
  value       = aws_ecs_cluster.this.name
}

output "backend_repository_arn" {
  description = "ARN del repositorio ECR del backend."
  value       = aws_ecr_repository.backend.arn
}

output "backend_repository_url" {
  description = "URL del repositorio ECR del backend."
  value       = aws_ecr_repository.backend.repository_url
}

output "backend_log_group_name" {
  description = "Nombre del grupo de logs del backend."
  value       = aws_cloudwatch_log_group.backend.name
}

output "backend_service_name" {
  description = "Nombre del servicio ECS del backend."
  value       = aws_ecs_service.backend.name
}

output "backend_task_family" {
  description = "Familia de task definitions actualizada por CD."
  value       = aws_ecs_task_definition.backend.family
}

output "backend_task_definition_arn" {
  description = "Task definition inicial; comienza sin tareas en ejecucion."
  value       = aws_ecs_task_definition.backend.arn
}
