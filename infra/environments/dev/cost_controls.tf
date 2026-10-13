# DEV se apaga al final de cada despliegue (cd-backend-dev.yml). Este apagado
# nocturno es el respaldo: cubre un apagado fallido y el reinicio automatico que
# AWS aplica a RDS despues de siete dias detenida. Corre de madrugada para no
# cortar un despliegue en curso.
data "aws_partition" "current" {}

locals {
  nightly_shutdown_role_name   = "${local.name_prefix}-nightly-shutdown"
  scheduler_schedule_group_arn = "arn:${data.aws_partition.current.partition}:scheduler:${var.aws_region}:${var.aws_account_id}:schedule-group/default"
  ecs_service_arn              = "arn:${data.aws_partition.current.partition}:ecs:${var.aws_region}:${var.aws_account_id}:service/${module.ecs.cluster_name}/${module.ecs.backend_service_name}"
  database_instance_arn        = "arn:${data.aws_partition.current.partition}:rds:${var.aws_region}:${var.aws_account_id}:db:${module.database.instance_id}"
}

data "aws_iam_policy_document" "nightly_shutdown_assume" {
  statement {
    sid     = "AllowEventBridgeScheduler"
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["scheduler.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [var.aws_account_id]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceArn"
      values   = [local.scheduler_schedule_group_arn]
    }
  }
}

resource "aws_iam_role" "nightly_shutdown" {
  name               = local.nightly_shutdown_role_name
  assume_role_policy = data.aws_iam_policy_document.nightly_shutdown_assume.json

  tags = {
    Name    = local.nightly_shutdown_role_name
    Purpose = "automatic-dev-cost-control"
  }
}

data "aws_iam_policy_document" "nightly_shutdown" {
  statement {
    sid       = "StopDevEcsService"
    actions   = ["ecs:UpdateService"]
    resources = [local.ecs_service_arn]
  }

  statement {
    sid       = "StopDevPostgreSql"
    actions   = ["rds:StopDBInstance"]
    resources = [local.database_instance_arn]
  }
}

resource "aws_iam_role_policy" "nightly_shutdown" {
  name   = "nightly-dev-shutdown"
  role   = aws_iam_role.nightly_shutdown.id
  policy = data.aws_iam_policy_document.nightly_shutdown.json
}

resource "aws_scheduler_schedule" "stop_ecs" {
  name                         = "${local.name_prefix}-stop-ecs"
  description                  = "Reduce ECS DEV a cero tareas todos los dias a las 04:00."
  state                        = var.enable_nightly_shutdown ? "ENABLED" : "DISABLED"
  schedule_expression          = "cron(0 4 * * ? *)"
  schedule_expression_timezone = var.nightly_shutdown_timezone

  depends_on = [aws_iam_role_policy.nightly_shutdown]

  flexible_time_window {
    mode = "OFF"
  }

  target {
    arn      = "arn:${data.aws_partition.current.partition}:scheduler:::aws-sdk:ecs:updateService"
    role_arn = aws_iam_role.nightly_shutdown.arn

    input = jsonencode({
      Cluster      = module.ecs.cluster_name
      Service      = module.ecs.backend_service_name
      DesiredCount = 0
    })

    retry_policy {
      maximum_event_age_in_seconds = 3600
      maximum_retry_attempts       = 2
    }
  }
}

resource "aws_scheduler_schedule" "stop_rds" {
  name                         = "${local.name_prefix}-stop-rds"
  description                  = "Detiene PostgreSQL DEV a las 04:15 luego de reducir ECS."
  state                        = var.enable_nightly_shutdown ? "ENABLED" : "DISABLED"
  schedule_expression          = "cron(15 4 * * ? *)"
  schedule_expression_timezone = var.nightly_shutdown_timezone

  depends_on = [aws_iam_role_policy.nightly_shutdown]

  flexible_time_window {
    mode = "OFF"
  }

  target {
    arn      = "arn:${data.aws_partition.current.partition}:scheduler:::aws-sdk:rds:stopDBInstance"
    role_arn = aws_iam_role.nightly_shutdown.arn

    input = jsonencode({
      DbInstanceIdentifier = module.database.instance_id
    })

    retry_policy {
      maximum_event_age_in_seconds = 3600
      maximum_retry_attempts       = 2
    }
  }
}
