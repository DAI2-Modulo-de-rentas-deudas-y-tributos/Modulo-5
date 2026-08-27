data "aws_partition" "current" {}

locals {
  nightly_shutdown_role_name   = "${local.name_prefix}-nightly-shutdown"
  scheduler_schedule_group_arn = "arn:${data.aws_partition.current.partition}:scheduler:${var.aws_region}:${var.aws_account_id}:schedule-group/default"
  ecs_service_arn              = "arn:${data.aws_partition.current.partition}:ecs:${var.aws_region}:${var.aws_account_id}:service/${module.ecs.cluster_name}/${module.ecs.backend_service_name}"
  database_instance_arn        = "arn:${data.aws_partition.current.partition}:rds:${var.aws_region}:${var.aws_account_id}:db:${module.database.instance_id}"
  budget_alert_email           = trimspace(var.budget_alert_email)

  budget_notifications = local.budget_alert_email == "" ? {} : {
    actual_50 = {
      threshold         = 50
      notification_type = "ACTUAL"
    }
    actual_80 = {
      threshold         = 80
      notification_type = "ACTUAL"
    }
    forecast_100 = {
      threshold         = 100
      notification_type = "FORECASTED"
    }
  }
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
    Purpose = "automatic-test-cost-control"
  }
}

data "aws_iam_policy_document" "nightly_shutdown" {
  statement {
    sid       = "StopTestEcsService"
    actions   = ["ecs:UpdateService"]
    resources = [local.ecs_service_arn]
  }

  statement {
    sid       = "StopTestPostgreSql"
    actions   = ["rds:StopDBInstance"]
    resources = [local.database_instance_arn]
  }
}

resource "aws_iam_role_policy" "nightly_shutdown" {
  name   = "nightly-test-shutdown"
  role   = aws_iam_role.nightly_shutdown.id
  policy = data.aws_iam_policy_document.nightly_shutdown.json
}

resource "aws_scheduler_schedule" "stop_ecs" {
  name                         = "${local.name_prefix}-stop-ecs"
  description                  = "Reduce ECS TEST a cero tareas todos los dias a las 22:00."
  state                        = var.enable_nightly_shutdown ? "ENABLED" : "DISABLED"
  schedule_expression          = "cron(0 22 * * ? *)"
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
  description                  = "Detiene PostgreSQL TEST a las 22:15 luego de reducir ECS."
  state                        = var.enable_nightly_shutdown ? "ENABLED" : "DISABLED"
  schedule_expression          = "cron(15 22 * * ? *)"
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

resource "aws_budgets_budget" "test_monthly" {
  name         = "${local.name_prefix}-monthly-cost"
  budget_type  = "COST"
  limit_amount = tostring(var.test_monthly_budget_limit_usd)
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  cost_filter {
    name   = "TagKeyValue"
    values = ["user:Environment$test"]
  }

  dynamic "notification" {
    for_each = local.budget_notifications

    content {
      comparison_operator        = "GREATER_THAN"
      threshold                  = notification.value.threshold
      threshold_type             = "PERCENTAGE"
      notification_type          = notification.value.notification_type
      subscriber_email_addresses = [local.budget_alert_email]
    }
  }

  tags = {
    Name    = "${local.name_prefix}-monthly-cost"
    Purpose = "test-cost-control"
  }
}
