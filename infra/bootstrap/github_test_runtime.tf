locals {
  test_backend_execution_role_arn = "arn:${data.aws_partition.current.partition}:iam::${data.aws_caller_identity.current.account_id}:role/${var.project_name}-test-backend-execution"
  test_backend_task_role_arn      = "arn:${data.aws_partition.current.partition}:iam::${data.aws_caller_identity.current.account_id}:role/${var.project_name}-test-backend-task"
  test_ecs_service_arn            = "arn:${data.aws_partition.current.partition}:ecs:${var.aws_region}:${data.aws_caller_identity.current.account_id}:service/${var.project_name}-test-cluster/${var.project_name}-test-backend"
  test_ecs_task_definition_arn    = "arn:${data.aws_partition.current.partition}:ecs:${var.aws_region}:${data.aws_caller_identity.current.account_id}:task-definition/${var.project_name}-test-backend:*"
  test_amplify_app_arn            = "arn:${data.aws_partition.current.partition}:amplify:${var.aws_region}:${data.aws_caller_identity.current.account_id}:apps/*"
  dev_backend_repository_arn      = "arn:${data.aws_partition.current.partition}:ecr:${var.aws_region}:${data.aws_caller_identity.current.account_id}:repository/${var.project_name}-dev-backend"
  test_nightly_shutdown_role_arn  = "arn:${data.aws_partition.current.partition}:iam::${data.aws_caller_identity.current.account_id}:role/${var.project_name}-test-nightly-shutdown"
  test_scheduler_schedule_arn     = "arn:${data.aws_partition.current.partition}:scheduler:${var.aws_region}:${data.aws_caller_identity.current.account_id}:schedule/default/${var.project_name}-test-stop-*"
  test_budget_arn                 = "arn:${data.aws_partition.current.partition}:budgets::${data.aws_caller_identity.current.account_id}:budget/${var.project_name}-test-monthly-cost"
}

data "aws_iam_policy_document" "github_test_runtime_compute" {
  statement {
    sid       = "GetEcrAuthorizationToken"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  statement {
    sid = "ReadDevBackendImageForPromotion"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:BatchGetImage",
      "ecr:DescribeImages",
      "ecr:GetDownloadUrlForLayer"
    ]
    resources = [local.dev_backend_repository_arn]
  }

  statement {
    sid = "PublishAndReadTestBackendImages"
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
    resources = [local.test_ecr_repository_arn]
  }

  statement {
    sid = "ReadTestEcsRuntime"
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
    sid       = "RegisterTestBackendTaskDefinitions"
    actions   = ["ecs:RegisterTaskDefinition"]
    resources = ["*"]
  }

  statement {
    sid = "ManageTestBackendTaskDefinitions"
    actions = [
      "ecs:DeregisterTaskDefinition",
      "ecs:TagResource",
      "ecs:UntagResource"
    ]
    resources = [local.test_ecs_task_definition_arn]
  }

  statement {
    sid = "ManageTestBackendService"
    actions = [
      "ecs:CreateService",
      "ecs:DeleteService",
      "ecs:TagResource",
      "ecs:UntagResource",
      "ecs:UpdateService"
    ]
    resources = [local.test_ecs_service_arn]
  }

  statement {
    sid = "ManageTestBackendRoles"
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
      local.test_backend_execution_role_arn,
      local.test_backend_task_role_arn,
      local.test_nightly_shutdown_role_arn
    ]
  }

  statement {
    sid     = "PassTestBackendRolesToEcs"
    actions = ["iam:PassRole"]
    resources = [
      local.test_backend_execution_role_arn,
      local.test_backend_task_role_arn
    ]

    condition {
      test     = "StringEquals"
      variable = "iam:PassedToService"
      values   = ["ecs-tasks.amazonaws.com"]
    }
  }

  statement {
    sid       = "PassTestShutdownRoleToScheduler"
    actions   = ["iam:PassRole"]
    resources = [local.test_nightly_shutdown_role_arn]

    condition {
      test     = "StringEquals"
      variable = "iam:PassedToService"
      values   = ["scheduler.amazonaws.com"]
    }
  }
}

resource "aws_iam_policy" "github_test_runtime_compute" {
  name        = "${var.project_name}-github-test-runtime-compute"
  description = "Permisos del pipeline TEST para ECR, ECS y roles de tareas."
  policy      = data.aws_iam_policy_document.github_test_runtime_compute.json
}

resource "aws_iam_role_policy_attachment" "github_test_runtime_compute" {
  role       = aws_iam_role.github_test.name
  policy_arn = aws_iam_policy.github_test_runtime_compute.arn
}

data "aws_iam_policy_document" "github_test_runtime_infrastructure" {
  statement {
    sid = "ManageTestSecurityGroups"
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
    sid = "ManageTestLoadBalancing"
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
    sid = "ManageTestPostgreSql"
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
      "rds:RemoveTagsFromResource",
      "rds:StartDBInstance",
      "rds:StopDBInstance"
    ]
    resources = ["*"]
  }

  statement {
    sid = "ManageTestRdsManagedCredentials"
    actions = [
      "secretsmanager:CreateSecret",
      "secretsmanager:TagResource"
    ]
    resources = [
      "arn:${data.aws_partition.current.partition}:secretsmanager:${var.aws_region}:${data.aws_caller_identity.current.account_id}:secret:rds!db-*"
    ]
  }

  statement {
    sid = "ManageTestNightlySchedules"
    actions = [
      "scheduler:CreateSchedule",
      "scheduler:DeleteSchedule",
      "scheduler:GetSchedule",
      "scheduler:UpdateSchedule"
    ]
    resources = [local.test_scheduler_schedule_arn]
  }

  statement {
    sid       = "ListTestNightlySchedules"
    actions   = ["scheduler:ListSchedules"]
    resources = ["*"]
  }

  statement {
    sid = "ManageTestMonthlyBudget"
    actions = [
      "budgets:ListTagsForResource",
      "budgets:ModifyBudget",
      "budgets:TagResource",
      "budgets:UntagResource",
      "budgets:ViewBudget"
    ]
    resources = [local.test_budget_arn]
  }

  statement {
    sid = "ManageBillingForTestBudget"
    actions = [
      "aws-portal:ModifyBilling",
      "aws-portal:ViewBilling"
    ]
    resources = ["*"]
  }

  statement {
    sid     = "DescribeTestManagedEncryptionKeys"
    actions = ["kms:DescribeKey"]
    resources = [
      "arn:${data.aws_partition.current.partition}:kms:${var.aws_region}:${data.aws_caller_identity.current.account_id}:key/*"
    ]
  }

  statement {
    sid = "ManageTestApiCloudFront"
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
    sid       = "ListTestAmplifyApps"
    actions   = ["amplify:CreateApp", "amplify:ListApps"]
    resources = ["*"]
  }

  statement {
    sid = "ManageAndDeployTestFrontend"
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
    resources = [local.test_amplify_app_arn]
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

resource "aws_iam_policy" "github_test_runtime_infrastructure" {
  name        = "${var.project_name}-github-test-runtime-infrastructure"
  description = "Permisos del pipeline Terraform para RDS, ALB, CloudFront y Amplify TEST."
  policy      = data.aws_iam_policy_document.github_test_runtime_infrastructure.json
}

resource "aws_iam_role_policy_attachment" "github_test_runtime_infrastructure" {
  role       = aws_iam_role.github_test.name
  policy_arn = aws_iam_policy.github_test_runtime_infrastructure.arn
}
