variable "name_prefix" {
  description = "Prefijo comun para los recursos PostgreSQL."
  type        = string
}

variable "subnet_ids" {
  description = "Subredes aisladas utilizadas por RDS."
  type        = list(string)

  validation {
    condition     = length(var.subnet_ids) >= 2
    error_message = "RDS requiere subredes en al menos dos zonas de disponibilidad."
  }
}

variable "security_group_id" {
  description = "Grupo de seguridad asignado a RDS."
  type        = string
}

variable "database_name" {
  description = "Nombre inicial de la base de datos."
  type        = string
  default     = "rentas"
}

variable "master_username" {
  description = "Usuario administrador; la clave es administrada por RDS en Secrets Manager."
  type        = string
  default     = "rentas_admin"
}

variable "port" {
  description = "Puerto de PostgreSQL."
  type        = number
  default     = 5432
}

variable "engine_version" {
  description = "Version mayor de PostgreSQL; RDS selecciona un minor vigente."
  type        = string
  default     = "16"
}

variable "instance_class" {
  description = "Clase de instancia RDS para DEV."
  type        = string
  default     = "db.t4g.micro"
}

variable "allocated_storage" {
  description = "Almacenamiento inicial gp3 en GiB."
  type        = number
  default     = 20
}

variable "max_allocated_storage" {
  description = "Limite de autoescalado de almacenamiento en GiB."
  type        = number
  default     = 50
}

variable "backup_retention_days" {
  description = "Dias de backups automatizados."
  type        = number
  default     = 1
}

variable "deletion_protection" {
  description = "Impide eliminar accidentalmente la instancia."
  type        = bool
  default     = false
}

variable "skip_final_snapshot" {
  description = "Evita el snapshot final al destruir el ambiente DEV."
  type        = bool
  default     = true
}
