data "aws_availability_zones" "available" {
  state = "available"
}

locals {
  project_name       = var.project_name
  environment        = "test"
  name_prefix        = "${local.project_name}-${local.environment}"
  availability_zones = slice(data.aws_availability_zones.available.names, 0, 2)
}

module "network" {
  source = "../../modules/network"

  name_prefix           = local.name_prefix
  vpc_cidr              = var.vpc_cidr
  availability_zones    = local.availability_zones
  public_subnet_cidrs   = var.public_subnet_cidrs
  database_subnet_cidrs = var.database_subnet_cidrs
}

module "security" {
  source = "../../modules/security"

  name_prefix            = local.name_prefix
  vpc_id                 = module.network.vpc_id
  backend_container_port = var.backend_container_port
  database_port          = var.database_port
}

module "database" {
  source = "../../modules/database"

  name_prefix           = local.name_prefix
  subnet_ids            = module.network.database_subnet_ids
  security_group_id     = module.security.database_security_group_id
  database_name         = var.database_name
  master_username       = var.database_master_username
  port                  = var.database_port
  engine_version        = var.postgres_engine_version
  instance_class        = var.database_instance_class
  allocated_storage     = var.database_allocated_storage
  max_allocated_storage = var.database_max_allocated_storage
  backup_retention_days = var.database_backup_retention_days
  deletion_protection   = var.database_deletion_protection
  skip_final_snapshot   = var.database_skip_final_snapshot
}

module "edge" {
  source = "../../modules/edge"

  name_prefix            = local.name_prefix
  vpc_id                 = module.network.vpc_id
  public_subnet_ids      = module.network.public_subnet_ids
  alb_security_group_id  = module.security.alb_security_group_id
  backend_container_port = var.backend_container_port
  health_check_path      = var.backend_health_check_path
  deletion_protection    = var.alb_deletion_protection
}

module "ecs" {
  source = "../../modules/ecs"

  name_prefix               = local.name_prefix
  aws_region                = var.aws_region
  ecr_images_to_keep        = var.ecr_images_to_keep
  enable_container_insights = var.enable_container_insights
  log_retention_days        = var.log_retention_days
  subnet_ids                = module.network.public_subnet_ids
  security_group_id         = module.security.backend_security_group_id
  target_group_arn          = module.edge.target_group_arn
  container_port            = var.backend_container_port
  task_cpu                  = var.backend_task_cpu
  task_memory               = var.backend_task_memory
  database_address          = module.database.address
  database_port             = module.database.port
  database_name             = module.database.database_name
  database_secret_arn       = module.database.master_secret_arn

  depends_on = [module.edge]
}

module "hosting" {
  source = "../../modules/hosting"

  name_prefix  = local.name_prefix
  branch_name  = "test"
  api_base_url = module.edge.api_base_url
}


