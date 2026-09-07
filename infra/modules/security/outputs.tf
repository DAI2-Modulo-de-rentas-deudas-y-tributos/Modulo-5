output "alb_security_group_id" {
  description = "Grupo de seguridad del ALB."
  value       = aws_security_group.alb.id
}

output "backend_security_group_id" {
  description = "Grupo de seguridad de las tareas Fargate."
  value       = aws_security_group.backend.id
}

output "database_security_group_id" {
  description = "Grupo de seguridad de PostgreSQL."
  value       = aws_security_group.database.id
}
