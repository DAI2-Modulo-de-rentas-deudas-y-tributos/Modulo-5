variable "project_name" {
  description = "Nombre común para los recursos del módulo."
  type        = string
  default     = "modulo-5-rentas"

  validation {
    condition     = can(regex("^[a-z0-9-]+$", var.project_name))
    error_message = "project_name solo puede contener minúsculas, números y guiones."
  }
}

variable "aws_region" {
  description = "Región AWS principal."
  type        = string
  default     = "us-east-2"
}

variable "github_owner" {
  description = "Organización propietaria del repositorio GitHub."
  type        = string
  default     = "DAI2-Modulo-de-rentas-deudas-y-tributos"
}

variable "github_repository" {
  description = "Nombre del repositorio GitHub."
  type        = string
  default     = "Modulo-5"
}

variable "github_dev_environment" {
  description = "Nombre exacto del GitHub Environment de DEV."
  type        = string
  default     = "dev"
}

variable "github_test_environment" {
  description = "Nombre exacto del GitHub Environment de TEST."
  type        = string
  default     = "test"
}

variable "github_prod_environment" {
  description = "Nombre exacto del GitHub Environment de PROD; debe aceptar solo la rama main."
  type        = string
  default     = "prod"
}
