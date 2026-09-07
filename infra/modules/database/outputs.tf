output "address" {
  description = "Hostname privado de PostgreSQL."
  value       = aws_db_instance.this.address
}

output "port" {
  description = "Puerto de PostgreSQL."
  value       = aws_db_instance.this.port
}

output "database_name" {
  description = "Nombre de la base de datos de la aplicacion."
  value       = aws_db_instance.this.db_name
}

output "master_secret_arn" {
  description = "ARN del secreto de credenciales administrado por RDS."
  value       = aws_db_instance.this.master_user_secret[0].secret_arn
}

output "instance_id" {
  description = "Identificador de la instancia RDS."
  value       = aws_db_instance.this.identifier
}

output "engine_version_actual" {
  description = "Version efectiva de PostgreSQL seleccionada por RDS."
  value       = aws_db_instance.this.engine_version_actual
}
