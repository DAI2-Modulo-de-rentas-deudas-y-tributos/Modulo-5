output "environment" {
  description = "Ambiente representado por esta configuración."
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
  description = "Subredes públicas para el futuro ALB y la conectividad de DEV."
  value       = module.network.public_subnet_ids
}

output "database_subnet_ids" {
  description = "Subredes aisladas para la futura base PostgreSQL."
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
  description = "Grupo de logs reservado para el backend."
  value       = module.ecs.backend_log_group_name
}
