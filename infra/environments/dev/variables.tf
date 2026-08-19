variable "project_name" {
  description = "Nombre utilizado para identificar los recursos del módulo."
  type        = string
  default     = "modulo-5-rentas"

  validation {
    condition     = can(regex("^[a-z0-9-]+$", var.project_name))
    error_message = "project_name solo puede contener minúsculas, números y guiones."
  }
}

variable "aws_region" {
  description = "Región AWS donde se desplegará el ambiente."
  type        = string
  default     = "sa-east-1"
}

variable "aws_account_id" {
  description = "Cuenta AWS autorizada para recibir el despliegue."
  type        = string
  default     = "060712744495"

  validation {
    condition     = can(regex("^[0-9]{12}$", var.aws_account_id))
    error_message = "aws_account_id debe contener exactamente 12 dígitos."
  }
}

variable "vpc_cidr" {
  description = "Bloque CIDR de la VPC DEV."
  type        = string
  default     = "10.50.0.0/16"
}

variable "public_subnet_cidrs" {
  description = "CIDR de las subredes públicas DEV."
  type        = list(string)
  default     = ["10.50.0.0/24", "10.50.1.0/24"]
}

variable "database_subnet_cidrs" {
  description = "CIDR de las subredes aisladas para PostgreSQL DEV."
  type        = list(string)
  default     = ["10.50.10.0/24", "10.50.11.0/24"]
}

variable "ecr_images_to_keep" {
  description = "Cantidad de imágenes del backend que se conservarán en ECR."
  type        = number
  default     = 20
}

variable "enable_container_insights" {
  description = "Habilita métricas ampliadas de ECS Container Insights."
  type        = bool
  default     = false
}

variable "log_retention_days" {
  description = "Días de retención de logs del backend."
  type        = number
  default     = 14
}
