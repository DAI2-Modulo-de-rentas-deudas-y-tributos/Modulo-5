variable "name_prefix" {
  description = "Prefijo comun para la plataforma de contenedores."
  type        = string
}

variable "aws_region" {
  description = "Region usada por el driver de logs."
  type        = string
}

variable "ecr_images_to_keep" {
  description = "Cantidad maxima de imagenes conservadas."
  type        = number
  default     = 20

  validation {
    condition     = var.ecr_images_to_keep >= 5 && var.ecr_images_to_keep <= 100
    error_message = "ecr_images_to_keep debe estar entre 5 y 100."
  }
}

variable "enable_container_insights" {
  description = "Habilita metricas ampliadas de Container Insights."
  type        = bool
  default     = false
}

variable "log_retention_days" {
  description = "Dias de retencion de logs del backend."
  type        = number
  default     = 14
}

variable "subnet_ids" {
  description = "Subredes publicas para las tareas Fargate de DEV."
  type        = list(string)
}

variable "security_group_id" {
  description = "Grupo de seguridad asignado a las tareas."
  type        = string
}

variable "target_group_arn" {
  description = "Target group del ALB."
  type        = string
}

variable "container_port" {
  description = "Puerto HTTP del contenedor."
  type        = number
  default     = 8080
}

variable "task_cpu" {
  description = "Unidades CPU de la tarea Fargate."
  type        = number
  default     = 256
}

variable "task_memory" {
  description = "Memoria MiB de la tarea Fargate."
  type        = number
  default     = 512
}

variable "database_address" {
  description = "Hostname privado de PostgreSQL."
  type        = string
}

variable "database_port" {
  description = "Puerto de PostgreSQL."
  type        = number
  default     = 5432
}

variable "database_name" {
  description = "Nombre de la base de datos."
  type        = string
}

variable "database_secret_arn" {
  description = "Secreto de credenciales administrado por RDS."
  type        = string
}
