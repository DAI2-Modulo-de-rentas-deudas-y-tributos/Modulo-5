variable "name_prefix" {
  description = "Prefijo comun para Amplify Hosting."
  type        = string
}

variable "branch_name" {
  description = "Rama logica publicada como ambiente DEV."
  type        = string
  default     = "develop"
}

variable "api_base_url" {
  description = "URL HTTPS inyectada durante el build del frontend."
  type        = string
}

variable "environment_variables" {
  description = "Variables no sensibles inyectadas durante el build del frontend."
  type        = map(string)
  default     = {}
}
