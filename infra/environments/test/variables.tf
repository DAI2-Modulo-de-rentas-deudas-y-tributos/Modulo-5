variable "project_name" {
  description = "Nombre utilizado para identificar los recursos del módulo."
  type        = string
  default     = "modulo-5-rentas"
}

variable "aws_region" {
  description = "Región AWS donde se desplegará el ambiente."
  type        = string
  default     = "sa-east-1"
}
