variable "project_name" {
  description = "Nombre utilizado para identificar los recursos del modulo."
  type        = string
  default     = "modulo-5-rentas"

  validation {
    condition     = can(regex("^[a-z0-9-]+$", var.project_name))
    error_message = "project_name solo puede contener minusculas, numeros y guiones."
  }
}

variable "aws_region" {
  description = "Region AWS donde se desplegara el ambiente."
  type        = string
  default     = "sa-east-1"
}

variable "aws_account_id" {
  description = "Cuenta AWS autorizada para recibir el despliegue."
  type        = string
  default     = "060712744495"

  validation {
    condition     = can(regex("^[0-9]{12}$", var.aws_account_id))
    error_message = "aws_account_id debe contener exactamente 12 digitos."
  }
}

variable "vpc_cidr" {
  description = "Bloque CIDR de la VPC TEST."
  type        = string
  default     = "10.60.0.0/16"
}

variable "public_subnet_cidrs" {
  description = "CIDR de las subredes publicas TEST."
  type        = list(string)
  default     = ["10.60.0.0/24", "10.60.1.0/24"]
}

variable "database_subnet_cidrs" {
  description = "CIDR de las subredes aisladas para PostgreSQL TEST."
  type        = list(string)
  default     = ["10.60.10.0/24", "10.60.11.0/24"]
}

variable "ecr_images_to_keep" {
  description = "Cantidad de imagenes del backend conservadas en ECR."
  type        = number
  default     = 20
}

variable "enable_container_insights" {
  description = "Habilita metricas ampliadas de ECS Container Insights."
  type        = bool
  default     = false
}

variable "log_retention_days" {
  description = "Dias de retencion de logs del backend."
  type        = number
  default     = 14
}

variable "backend_container_port" {
  description = "Puerto HTTP esperado por el contrato del backend."
  type        = number
  default     = 8080
}

variable "backend_health_check_path" {
  description = "Ruta de Spring Boot Actuator comprobada por el ALB."
  type        = string
  default     = "/actuator/health"
}

variable "backend_task_cpu" {
  description = "CPU de la tarea Fargate TEST."
  type        = number
  default     = 256
}

variable "backend_task_memory" {
  description = "Memoria MiB de la tarea Fargate TEST."
  type        = number
  default     = 512
}

variable "database_name" {
  description = "Nombre inicial de PostgreSQL."
  type        = string
  default     = "rentas"
}

variable "database_master_username" {
  description = "Usuario maestro; la clave se genera en Secrets Manager."
  type        = string
  default     = "rentas_admin"
}

variable "database_port" {
  description = "Puerto privado de PostgreSQL."
  type        = number
  default     = 5432
}

variable "postgres_engine_version" {
  description = "Version mayor de PostgreSQL."
  type        = string
  default     = "16"
}

variable "database_instance_class" {
  description = "Clase RDS de bajo costo para TEST."
  type        = string
  default     = "db.t4g.micro"
}

variable "database_allocated_storage" {
  description = "Almacenamiento inicial gp3 en GiB."
  type        = number
  default     = 20
}

variable "database_max_allocated_storage" {
  description = "Limite de autoescalado del almacenamiento en GiB."
  type        = number
  default     = 50
}

variable "database_backup_retention_days" {
  description = "Retencion de backups automatizados en TEST."
  type        = number
  default     = 1
}

variable "database_deletion_protection" {
  description = "Proteccion contra eliminacion de RDS."
  type        = bool
  default     = false
}

variable "database_skip_final_snapshot" {
  description = "Omite el snapshot final al destruir TEST."
  type        = bool
  default     = true
}

variable "alb_deletion_protection" {
  description = "Proteccion contra eliminacion del ALB."
  type        = bool
  default     = false
}


