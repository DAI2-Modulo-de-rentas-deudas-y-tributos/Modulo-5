locals {
  dev_backend_execution_role_arn = "arn:${data.aws_partition.current.partition}:iam::${data.aws_caller_identity.current.account_id}:role/${var.project_name}-dev-backend-execution"
  dev_backend_task_role_arn      = "arn:${data.aws_partition.current.partition}:iam::${data.aws_caller_identity.current.account_id}:role/${var.project_name}-dev-backend-task"
  dev_ecs_service_arn            = "arn:${data.aws_partition.current.partition}:ecs:${var.aws_region}:${data.aws_caller_identity.current.account_id}:service/${var.project_name}-dev-cluster/${var.project_name}-dev-backend"
  dev_ecs_task_definition_arn    = "arn:${data.aws_partition.current.partition}:ecs:${var.aws_region}:${data.aws_caller_identity.current.account_id}:task-definition/${var.project_name}-dev-backend:*"
  dev_amplify_app_arn            = "arn:${data.aws_partition.current.partition}:amplify:${var.aws_region}:${data.aws_caller_identity.current.account_id}:apps/*"
}

data "aws_iam_policy_document" "github_dev_runtime_compute" {
  statement {
    sid       = "GetEcrAuthorizationToken"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  statement {
    sid = "PublishAndReadDevBackendImages"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:BatchGetImage",
      "ecr:CompleteLayerUpload",
      "ecr:DescribeImages",
      "ecr:GetDownloadUrlForLayer",
      "ecr:InitiateLayerUpload",
      "ecr:ListImages",
      "ecr:PutImage",
      "ecr:UploadLayerPart"
    ]
    resources = [local.dev_ecr_repository_arn]
  }

  statement {
    sid = "ReadDevEcsRuntime"
    actions = [
      "ecs:DescribeServices",
      "ecs:DescribeTaskDefinition",
      "ecs:ListServices",
      "ecs:ListTagsForResource",
      "ecs:ListTaskDefinitions"
    ]
    resources = ["*"]
  }

  statement {
    sid       = "RegisterDevBackendTaskDefinitions"
    actions   = ["ecs:RegisterTaskDefinition"]
    resources = ["*"]
  }

  statement {
    sid = "ManageDevBackendTaskDefinitions"
    actions = [
      "ecs:DeregisterTaskDefinition",
      "ecs:TagResource",
      "ecs:UntagResource"
    ]
    resources = [local.dev_ecs_task_definition_arn]
  }

  statement {
    sid = "ManageDevBackendService"
    actions = [
      "ecs:CreateService",
      "ecs:DeleteService",
      "ecs:TagResource",
      "ecs:UntagResource",
      "ecs:UpdateService"
    ]
    resources = [local.dev_ecs_service_arn]
  }

  statement {
    sid = "ManageDevBackendRoles"
    actions = [
      "iam:AttachRolePolicy",
      "iam:CreateRole",
      "iam:DeleteRole",
      "iam:DeleteRolePolicy",
      "iam:DetachRolePolicy",
      "iam:GetRole",
      "iam:GetRolePolicy",
      "iam:ListAttachedRolePolicies",
      "iam:ListRolePolicies",
      "iam:ListRoleTags",
      "iam:PutRolePolicy",
      "iam:TagRole",
      "iam:UntagRole",
      "iam:UpdateAssumeRolePolicy"
    ]
    resources = [
      local.dev_backend_execution_role_arn,
      local.dev_backend_task_role_arn
    ]
  }

  statement {
    sid     = "PassDevBackendRolesToEcs"
    actions = ["iam:PassRole"]
    resources = [
      local.dev_backend_execution_role_arn,
      local.dev_backend_task_role_arn
    ]

    condition {
      test     = "StringEquals"
      variable = "iam:PassedToService"
      values   = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_policy" "github_dev_runtime_compute" {
  name        = "${var.project_name}-github-dev-runtime-compute"
  description = "Permisos del pipeline DEV para ECR, ECS y roles de tareas."
  policy      = data.aws_iam_policy_document.github_dev_runtime_compute.json
}

resource "aws_iam_role_policy_attachment" "github_dev_runtime_compute" {
  role       = aws_iam_role.github_dev.name
  policy_arn = aws_iam_policy.github_dev_runtime_compute.arn
}

data "aws_iam_policy_document" "github_dev_runtime_infrastructure" {
  statement {
    sid = "ManageDevSecurityGroups"
    actions = [
      "ec2:AuthorizeSecurityGroupEgress",
      "ec2:AuthorizeSecurityGroupIngress",
      "ec2:CreateSecurityGroup",
      "ec2:DeleteSecurityGroup",
      "ec2:ModifySecurityGroupRules",
      "ec2:RevokeSecurityGroupEgress",
      "ec2:RevokeSecurityGroupIngress",
      "ec2:UpdateSecurityGroupRuleDescriptionsEgress",
      "ec2:UpdateSecurityGroupRuleDescriptionsIngress"
    ]
    resources = ["*"]
  }

  statement {
    sid = "ManageDevLoadBalancing"
    actions = [
      "elasticloadbalancing:AddTags",
      "elasticloadbalancing:CreateListener",
      "elasticloadbalancing:CreateLoadBalancer",
      "elasticloadbalancing:CreateTargetGroup",
      "elasticloadbalancing:DeleteListener",
      "elasticloadbalancing:DeleteLoadBalancer",
      "elasticloadbalancing:DeleteTargetGroup",
      "elasticloadbalancing:Describe*",
      "elasticloadbalancing:ModifyListener",
      "elasticloadbalancing:ModifyLoadBalancerAttributes",
      "elasticloadbalancing:ModifyTargetGroup",
      "elasticloadbalancing:ModifyTargetGroupAttributes",
      "elasticloadbalancing:RemoveTags",
      "elasticloadbalancing:SetSecurityGroups",
      "elasticloadbalancing:SetSubnets"
    ]
    resources = ["*"]
  }

  statement {
    sid = "ManageDevPostgreSql"
    actions = [
      "rds:AddTagsToResource",
      "rds:CreateDBInstance",
      "rds:CreateDBSubnetGroup",
      "rds:DeleteDBInstance",
      "rds:DeleteDBSubnetGroup",
      "rds:Describe*",
      "rds:ListTagsForResource",
      "rds:ModifyDBInstance",
      "rds:ModifyDBSubnetGroup",
      "rds:RemoveTagsFromResource"
    ]
    resources = ["*"]
  }

  statement {
    sid = "ManageDevRdsManagedCredentials"
    actions = [
      "secretsmanager:CreateSecret",
      "secretsmanager:TagResource"
    ]
    resources = [
      "arn:${data.aws_partition.current.partition}:secretsmanager:${var.aws_region}:${data.aws_caller_identity.current.account_id}:secret:rds!db-*"
    ]
  }

  statement {
    sid     = "DescribeDevManagedEncryptionKeys"
    actions = ["kms:DescribeKey"]
    resources = [
      "arn:${data.aws_partition.current.partition}:kms:${var.aws_region}:${data.aws_caller_identity.current.account_id}:key/*"
    ]
  }

  statement {
    sid = "ManageDevApiCloudFront"
    actions = [
      "cloudfront:CreateDistribution",
      "cloudfront:DeleteDistribution",
      "cloudfront:GetCachePolicy",
      "cloudfront:GetDistribution",
      "cloudfront:GetDistributionConfig",
      "cloudfront:GetOriginRequestPolicy",
      "cloudfront:ListCachePolicies",
      "cloudfront:ListDistributions",
      "cloudfront:ListOriginRequestPolicies",
      "cloudfront:ListTagsForResource",
      "cloudfront:TagResource",
      "cloudfront:UntagResource",
      "cloudfront:UpdateDistribution"
    ]
    resources = ["*"]
  }

  statement {
    sid       = "ListDevAmplifyApps"
    actions   = ["amplify:CreateApp", "amplify:ListApps"]
    resources = ["*"]
  }

  statement {
    sid = "ManageAndDeployDevFrontend"
    actions = [
      "amplify:CreateBranch",
      "amplify:CreateDeployment",
      "amplify:DeleteApp",
      "amplify:DeleteBranch",
      "amplify:GetApp",
      "amplify:GetBranch",
      "amplify:GetJob",
      "amplify:ListBranches",
      "amplify:ListJobs",
      "amplify:ListTagsForResource",
      "amplify:StartDeployment",
      "amplify:TagResource",
      "amplify:UntagResource",
      "amplify:UpdateApp",
      "amplify:UpdateBranch"
    ]
    resources = [local.dev_amplify_app_arn]
  }

  statement {
    sid     = "CreateRuntimeServiceLinkedRoles"
    actions = ["iam:CreateServiceLinkedRole"]
    resources = [
      "arn:${data.aws_partition.current.partition}:iam::*:role/aws-service-role/elasticloadbalancing.amazonaws.com/AWSServiceRoleForElasticLoadBalancing*",
      "arn:${data.aws_partition.current.partition}:iam::*:role/aws-service-role/rds.amazonaws.com/AWSServiceRoleForRDS*"
    ]

    condition {
      test     = "StringEquals"
      variable = "iam:AWSServiceName"
      values = [
        "elasticloadbalancing.amazonaws.com",
        "rds.amazonaws.com"
      ]
    }
  }
}

resource "aws_iam_policy" "github_dev_runtime_infrastructure" {
  name        = "${var.project_name}-github-dev-runtime-infrastructure"
  description = "Permisos del pipeline Terraform para RDS, ALB, CloudFront y Amplify DEV."
  policy      = data.aws_iam_policy_document.github_dev_runtime_infrastructure.json
}

resource "aws_iam_role_policy_attachment" "github_dev_runtime_infrastructure" {
  role       = aws_iam_role.github_dev.name
  policy_arn = aws_iam_policy.github_dev_runtime_infrastructure.arn
}
