variable "name_prefix" {
  description = "Prefijo común para los recursos de la plataforma de contenedores."
  type        = string

  validation {
    condition     = can(regex("^[a-z0-9-]+$", var.name_prefix))
    error_message = "name_prefix solo puede contener minúsculas, números y guiones."
  }
}

variable "ecr_images_to_keep" {
  description = "Cantidad máxima de imágenes conservadas por la política de ciclo de vida."
  type        = number
  default     = 20

  validation {
    condition     = var.ecr_images_to_keep >= 5 && var.ecr_images_to_keep <= 100
    error_message = "ecr_images_to_keep debe estar entre 5 y 100."
  }
}

variable "enable_container_insights" {
  description = "Habilita métricas ampliadas de Container Insights."
  type        = bool
  default     = false
}

variable "log_retention_days" {
  description = "Días de retención de los logs del backend en CloudWatch."
  type        = number
  default     = 14

  validation {
    condition     = contains([1, 3, 5, 7, 14, 30, 60, 90, 120, 150, 180, 365], var.log_retention_days)
    error_message = "log_retention_days debe ser un período admitido por CloudWatch Logs."
  }
}
