variable "name_prefix" {
  description = "Prefijo común para nombrar los recursos de red."
  type        = string

  validation {
    condition     = can(regex("^[a-z0-9-]+$", var.name_prefix))
    error_message = "name_prefix solo puede contener minúsculas, números y guiones."
  }
}

variable "vpc_cidr" {
  description = "Bloque CIDR IPv4 de la VPC."
  type        = string

  validation {
    condition     = can(cidrhost(var.vpc_cidr, 0))
    error_message = "vpc_cidr debe ser un bloque CIDR IPv4 válido."
  }
}

variable "availability_zones" {
  description = "Dos zonas de disponibilidad para distribuir las subredes."
  type        = list(string)

  validation {
    condition     = length(var.availability_zones) == 2 && length(distinct(var.availability_zones)) == 2
    error_message = "Se requieren exactamente dos zonas de disponibilidad diferentes."
  }
}

variable "public_subnet_cidrs" {
  description = "Bloques CIDR de las dos subredes públicas."
  type        = list(string)

  validation {
    condition = (
      length(var.public_subnet_cidrs) == 2 &&
      alltrue([for cidr in var.public_subnet_cidrs : can(cidrhost(cidr, 0))])
    )
    error_message = "Se requieren exactamente dos bloques CIDR válidos para las subredes públicas."
  }
}

variable "database_subnet_cidrs" {
  description = "Bloques CIDR de las dos subredes aisladas de base de datos."
  type        = list(string)

  validation {
    condition = (
      length(var.database_subnet_cidrs) == 2 &&
      alltrue([for cidr in var.database_subnet_cidrs : can(cidrhost(cidr, 0))])
    )
    error_message = "Se requieren exactamente dos bloques CIDR válidos para las subredes de base de datos."
  }
}
