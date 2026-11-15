locals {
  github_legacy_subject_prefix = "repo:${var.github_owner}/${var.github_repository}"

  # GitHub comenzó a incorporar IDs inmutables en repositorios nuevos durante 2026.
  # Se aceptan ambos formatos, siempre limitados a esta organización y repositorio.
  github_immutable_subject_prefix = "repo:${var.github_owner}@*/${var.github_repository}@*"

  dev_state_key  = "environments/dev/terraform.tfstate"
  test_state_key = "environments/test/terraform.tfstate"
  prod_state_key = "environments/prod/terraform.tfstate"
}

resource "aws_iam_openid_connect_provider" "github" {
  url = "https://token.actions.githubusercontent.com"

  client_id_list = [
    "sts.amazonaws.com"
  ]

  tags = {
    Purpose = "github-actions"
  }
}

data "aws_iam_policy_document" "github_dev_trust" {
  statement {
    sid     = "GitHubActionsDev"
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github.arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    condition {
      test     = "StringLike"
      variable = "token.actions.githubusercontent.com:sub"
      values = [
        "${local.github_legacy_subject_prefix}:environment:${var.github_dev_environment}",
        "${local.github_immutable_subject_prefix}:environment:${var.github_dev_environment}"
      ]
    }
  }
}

data "aws_iam_policy_document" "github_test_trust" {
  statement {
    sid     = "GitHubActionsTest"
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github.arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    condition {
      test     = "StringLike"
      variable = "token.actions.githubusercontent.com:sub"
      values = [
        "${local.github_legacy_subject_prefix}:environment:${var.github_test_environment}",
        "${local.github_immutable_subject_prefix}:environment:${var.github_test_environment}"
      ]
    }
  }
}

data "aws_iam_policy_document" "github_prod_trust" {
  statement {
    sid     = "GitHubActionsProd"
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github.arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    condition {
      test     = "StringLike"
      variable = "token.actions.githubusercontent.com:sub"
      values = [
        "${local.github_legacy_subject_prefix}:environment:${var.github_prod_environment}",
        "${local.github_immutable_subject_prefix}:environment:${var.github_prod_environment}"
      ]
    }
  }
}

resource "aws_iam_role" "github_dev" {
  name                 = "${var.project_name}-github-dev-deploy"
  description          = "Rol de GitHub Actions para desplegar el ambiente DEV."
  assume_role_policy   = data.aws_iam_policy_document.github_dev_trust.json
  max_session_duration = 3600

  tags = {
    GitHubEnvironment = var.github_dev_environment
  }
}

resource "aws_iam_role" "github_test" {
  name                 = "${var.project_name}-github-test-deploy"
  description          = "Rol de GitHub Actions para desplegar el ambiente TEST."
  assume_role_policy   = data.aws_iam_policy_document.github_test_trust.json
  max_session_duration = 3600

  tags = {
    GitHubEnvironment = var.github_test_environment
  }
}

resource "aws_iam_role" "github_prod" {
  name                 = "${var.project_name}-github-prod-deploy"
  description          = "Rol de GitHub Actions para desplegar el ambiente PROD."
  assume_role_policy   = data.aws_iam_policy_document.github_prod_trust.json
  max_session_duration = 3600

  tags = {
    GitHubEnvironment = var.github_prod_environment
  }
}

data "aws_iam_policy_document" "github_dev_state" {
  statement {
    sid       = "GetBucketLocation"
    actions   = ["s3:GetBucketLocation"]
    resources = [aws_s3_bucket.terraform_state.arn]
  }

  statement {
    sid       = "ListDevState"
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.terraform_state.arn]

    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values   = ["environments/dev/*"]
    }
  }

  statement {
    sid = "ReadWriteDevState"
    actions = [
      "s3:GetObject",
      "s3:PutObject"
    ]
    resources = ["${aws_s3_bucket.terraform_state.arn}/${local.dev_state_key}"]
  }

  statement {
    sid = "ManageDevStateLock"
    actions = [
      "s3:GetObject",
      "s3:PutObject",
      "s3:DeleteObject"
    ]
    resources = ["${aws_s3_bucket.terraform_state.arn}/${local.dev_state_key}.tflock"]
  }
}

data "aws_iam_policy_document" "github_test_state" {
  statement {
    sid       = "GetBucketLocation"
    actions   = ["s3:GetBucketLocation"]
    resources = [aws_s3_bucket.terraform_state.arn]
  }

  statement {
    sid       = "ListTestState"
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.terraform_state.arn]

    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values   = ["environments/test/*"]
    }
  }

  statement {
    sid = "ReadWriteTestState"
    actions = [
      "s3:GetObject",
      "s3:PutObject"
    ]
    resources = ["${aws_s3_bucket.terraform_state.arn}/${local.test_state_key}"]
  }

  statement {
    sid = "ManageTestStateLock"
    actions = [
      "s3:GetObject",
      "s3:PutObject",
      "s3:DeleteObject"
    ]
    resources = ["${aws_s3_bucket.terraform_state.arn}/${local.test_state_key}.tflock"]
  }
}

data "aws_iam_policy_document" "github_prod_state" {
  statement {
    sid       = "GetBucketLocation"
    actions   = ["s3:GetBucketLocation"]
    resources = [aws_s3_bucket.terraform_state.arn]
  }

  statement {
    sid       = "ListProdState"
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.terraform_state.arn]

    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values   = ["environments/prod/*"]
    }
  }

  statement {
    sid = "ReadWriteProdState"
    actions = [
      "s3:GetObject",
      "s3:PutObject"
    ]
    resources = ["${aws_s3_bucket.terraform_state.arn}/${local.prod_state_key}"]
  }

  statement {
    sid = "ManageProdStateLock"
    actions = [
      "s3:GetObject",
      "s3:PutObject",
      "s3:DeleteObject"
    ]
    resources = ["${aws_s3_bucket.terraform_state.arn}/${local.prod_state_key}.tflock"]
  }
}

resource "aws_iam_role_policy" "github_dev_state" {
  name   = "terraform-state-dev"
  role   = aws_iam_role.github_dev.id
  policy = data.aws_iam_policy_document.github_dev_state.json
}

resource "aws_iam_role_policy" "github_test_state" {
  name   = "terraform-state-test"
  role   = aws_iam_role.github_test.id
  policy = data.aws_iam_policy_document.github_test_state.json
}

resource "aws_iam_role_policy" "github_prod_state" {
  name   = "terraform-state-prod"
  role   = aws_iam_role.github_prod.id
  policy = data.aws_iam_policy_document.github_prod_state.json
}

# DEV y TEST administran RDS, ALB, VPC y CloudFront sobre "*". Esta denegación
# explícita impide que esos roles modifiquen o borren recursos de PROD, que
# comparten la cuenta y llevan la etiqueta Environment=prod.
data "aws_iam_policy_document" "deny_prod_resources" {
  statement {
    sid       = "DenyProdTaggedResources"
    effect    = "Deny"
    actions   = ["*"]
    resources = ["*"]

    condition {
      test     = "StringEquals"
      variable = "aws:ResourceTag/Environment"
      values   = ["prod"]
    }
  }
}

resource "aws_iam_role_policy" "github_dev_deny_prod" {
  name   = "deny-prod-resources"
  role   = aws_iam_role.github_dev.id
  policy = data.aws_iam_policy_document.deny_prod_resources.json
}

resource "aws_iam_role_policy" "github_test_deny_prod" {
  name   = "deny-prod-resources"
  role   = aws_iam_role.github_test.id
  policy = data.aws_iam_policy_document.deny_prod_resources.json
}
