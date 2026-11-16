# Buckets de access logs: "bootstrap" recibe los del bucket de estado; cada ambiente, los de su
# ALB (prefijo alb/) y su distribución de CloudFront (prefijo cloudfront/). Viven en el
# bootstrap para que destruir un ambiente (DEV es efímero) no se lleve su evidencia.
resource "aws_s3_bucket" "access_logs" {
  for_each = toset(["bootstrap", "dev", "test", "prod"])

  bucket = lower(
    "${var.project_name}-${each.key}-access-logs-${data.aws_caller_identity.current.account_id}-${var.aws_region}"
  )

  tags = {
    Environment = each.key
    Purpose     = "access-logs"
  }
}

resource "aws_s3_bucket_versioning" "access_logs" {
  for_each = aws_s3_bucket.access_logs

  bucket = each.value.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "access_logs" {
  for_each = aws_s3_bucket.access_logs

  bucket = each.value.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "access_logs" {
  for_each = aws_s3_bucket.access_logs

  bucket = each.value.id

  rule {
    id     = "expire-access-logs"
    status = "Enabled"

    filter {}

    expiration {
      days = 30
    }

    noncurrent_version_expiration {
      noncurrent_days = 30
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }

  depends_on = [aws_s3_bucket_versioning.access_logs]
}

# Los logs estándar de CloudFront se entregan por ACL, así que los buckets de los ambientes
# no pueden tener las ACL deshabilitadas; el del bootstrap sí.
resource "aws_s3_bucket_ownership_controls" "access_logs" {
  for_each = aws_s3_bucket.access_logs

  bucket = each.value.id

  rule {
    object_ownership = each.key == "bootstrap" ? "BucketOwnerEnforced" : "BucketOwnerPreferred"
  }
}

resource "aws_s3_bucket_public_access_block" "access_logs" {
  for_each = aws_s3_bucket.access_logs

  bucket = each.value.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

data "aws_iam_policy_document" "access_logs" {
  for_each = aws_s3_bucket.access_logs

  statement {
    sid    = "DenyInsecureTransport"
    effect = "Deny"

    actions = ["s3:*"]
    resources = [
      each.value.arn,
      "${each.value.arn}/*"
    ]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }

  dynamic "statement" {
    for_each = each.key == "bootstrap" ? [aws_s3_bucket.terraform_state.arn] : []

    content {
      sid       = "DeliverTerraformStateAccessLogs"
      actions   = ["s3:PutObject"]
      resources = ["${each.value.arn}/terraform-state/*"]

      principals {
        type        = "Service"
        identifiers = ["logging.s3.amazonaws.com"]
      }

      condition {
        test     = "ArnEquals"
        variable = "aws:SourceArn"
        values   = [statement.value]
      }

      condition {
        test     = "StringEquals"
        variable = "aws:SourceAccount"
        values   = [data.aws_caller_identity.current.account_id]
      }
    }
  }

  dynamic "statement" {
    for_each = each.key == "bootstrap" ? [] : [each.key]

    content {
      sid       = "DeliverAlbAccessLogs"
      actions   = ["s3:PutObject"]
      resources = ["${each.value.arn}/alb/AWSLogs/${data.aws_caller_identity.current.account_id}/*"]

      principals {
        type        = "Service"
        identifiers = ["logdelivery.elasticloadbalancing.amazonaws.com"]
      }

      condition {
        test     = "ArnLike"
        variable = "aws:SourceArn"
        values = [
          "arn:${data.aws_partition.current.partition}:elasticloadbalancing:${var.aws_region}:${data.aws_caller_identity.current.account_id}:loadbalancer/*"
        ]
      }
    }
  }
}

resource "aws_s3_bucket_policy" "access_logs" {
  for_each = aws_s3_bucket.access_logs

  bucket = each.value.id
  policy = data.aws_iam_policy_document.access_logs[each.key].json
}

data "aws_canonical_user_id" "current" {}

data "aws_cloudfront_log_delivery_canonical_user_id" "current" {}

resource "aws_s3_bucket_acl" "cloudfront_logs" {
  for_each = local.github_deploy_roles

  bucket = aws_s3_bucket.access_logs[each.key].id

  access_control_policy {
    grant {
      grantee {
        id   = data.aws_canonical_user_id.current.id
        type = "CanonicalUser"
      }
      permission = "FULL_CONTROL"
    }

    grant {
      grantee {
        id   = data.aws_cloudfront_log_delivery_canonical_user_id.current.id
        type = "CanonicalUser"
      }
      permission = "FULL_CONTROL"
    }

    owner {
      id = data.aws_canonical_user_id.current.id
    }
  }

  depends_on = [aws_s3_bucket_ownership_controls.access_logs]
}

# Crear o modificar una distribución con logging_config exige leer y escribir la ACL del bucket.
resource "aws_iam_role_policy" "access_logs" {
  for_each = local.github_deploy_roles

  name = "cloudfront-access-log-delivery"
  role = each.value.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["s3:GetBucketAcl", "s3:PutBucketAcl"]
        Resource = aws_s3_bucket.access_logs[each.key].arn
      }
    ]
  })
}
