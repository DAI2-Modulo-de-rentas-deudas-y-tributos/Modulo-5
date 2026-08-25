output "environment" {
  description = "Ambiente representado por esta configuracion."
  value       = local.environment
}

output "availability_zones" {
  description = "Zonas de disponibilidad utilizadas por DEV."
  value       = module.network.availability_zones
}

output "vpc_id" {
  description = "ID de la VPC DEV."
  value       = module.network.vpc_id
}

output "public_subnet_ids" {
  description = "Subredes publicas utilizadas por ALB y Fargate."
  value       = module.network.public_subnet_ids
}

output "database_subnet_ids" {
  description = "Subredes aisladas utilizadas por PostgreSQL."
  value       = module.network.database_subnet_ids
}

output "ecs_cluster_name" {
  description = "Nombre del cluster ECS DEV."
  value       = module.ecs.cluster_name
}

output "backend_ecr_repository_url" {
  description = "URL del repositorio ECR del backend."
  value       = module.ecs.backend_repository_url
}

output "backend_log_group_name" {
  description = "Grupo de logs del backend."
  value       = module.ecs.backend_log_group_name
}

output "backend_service_name" {
  description = "Servicio ECS actualizado por el pipeline del backend."
  value       = module.ecs.backend_service_name
}

output "backend_task_family" {
  description = "Familia de task definitions del backend."
  value       = module.ecs.backend_task_family
}

output "postgres_instance_id" {
  description = "Identificador de PostgreSQL DEV."
  value       = module.database.instance_id
}

output "postgres_engine_version" {
  description = "Version efectiva de PostgreSQL DEV."
  value       = module.database.engine_version_actual
}

output "database_secret_arn" {
  description = "ARN del secreto RDS; no contiene el valor de las credenciales."
  value       = module.database.master_secret_arn
}

output "api_base_url" {
  description = "URL publica HTTPS de la API."
  value       = module.edge.api_base_url
}

output "frontend_amplify_app_id" {
  description = "Identificador utilizado por el pipeline del frontend."
  value       = module.hosting.app_id
}

output "frontend_url" {
  description = "URL publica HTTPS del frontend DEV."
  value       = module.hosting.frontend_url
}
