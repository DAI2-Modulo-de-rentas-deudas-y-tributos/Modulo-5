locals {
  test_ecr_repository_arn = "arn:${data.aws_partition.current.partition}:ecr:${var.aws_region}:${data.aws_caller_identity.current.account_id}:repository/${var.project_name}-test-backend"
  test_ecs_cluster_arn    = "arn:${data.aws_partition.current.partition}:ecs:${var.aws_region}:${data.aws_caller_identity.current.account_id}:cluster/${var.project_name}-test-cluster"
  test_log_group_arn      = "arn:${data.aws_partition.current.partition}:logs:${var.aws_region}:${data.aws_caller_identity.current.account_id}:log-group:/ecs/${var.project_name}-test/backend:*"
}

data "aws_iam_policy_document" "github_test_foundation" {
  statement {
    sid = "ReadNetworkState"
    actions = [
      "ec2:Describe*",
      "ec2:GetManagedPrefixListEntries"
    ]
    resources = ["*"]
  }

  statement {
    sid = "ManageTestNetworkFoundation"
    actions = [
      "ec2:AssociateRouteTable",
      "ec2:AttachInternetGateway",
      "ec2:CreateInternetGateway",
      "ec2:CreateRoute",
      "ec2:CreateRouteTable",
      "ec2:CreateSubnet",
      "ec2:CreateTags",
      "ec2:CreateVpc",
      "ec2:DeleteInternetGateway",
      "ec2:DeleteRoute",
      "ec2:DeleteRouteTable",
      "ec2:DeleteSubnet",
      "ec2:DeleteTags",
      "ec2:DeleteVpc",
      "ec2:DetachInternetGateway",
      "ec2:DisassociateRouteTable",
      "ec2:ModifySubnetAttribute",
      "ec2:ModifyVpcAttribute",
      "ec2:ReplaceRoute",
      "ec2:ReplaceRouteTableAssociation"
    ]
    resources = ["*"]
  }

  statement {
    sid = "ManageTestBackendRepository"
    actions = [
      "ecr:CreateRepository",
      "ecr:DeleteLifecyclePolicy",
      "ecr:DeleteRepository",
      "ecr:DescribeRepositories",
      "ecr:GetLifecyclePolicy",
      "ecr:GetRepositoryPolicy",
      "ecr:ListTagsForResource",
      "ecr:PutImageScanningConfiguration",
      "ecr:PutImageTagMutability",
      "ecr:PutLifecyclePolicy",
      "ecr:TagResource",
      "ecr:UntagResource"
    ]
    resources = [local.test_ecr_repository_arn]
  }

  statement {
    sid = "ManageTestEcsCluster"
    actions = [
      "ecs:CreateCluster",
      "ecs:DeleteCluster",
      "ecs:DescribeClusters",
      "ecs:ListTagsForResource",
      "ecs:TagResource",
      "ecs:UntagResource",
      "ecs:UpdateCluster",
      "ecs:UpdateClusterSettings"
    ]
    resources = [local.test_ecs_cluster_arn]
  }

  statement {
    sid     = "CreateEcsServiceLinkedRole"
    actions = ["iam:CreateServiceLinkedRole"]
    resources = [
      "arn:${data.aws_partition.current.partition}:iam::*:role/aws-service-role/ecs.amazonaws.com/AWSServiceRoleForECS*"
    ]

    condition {
      test     = "StringEquals"
      variable = "iam:AWSServiceName"
      values   = ["ecs.amazonaws.com"]
    }
  }

  statement {
    sid       = "ReadTestLogGroups"
    actions   = ["logs:DescribeLogGroups"]
    resources = ["*"]
  }

  statement {
    sid = "ManageTestBackendLogGroup"
    actions = [
      "logs:CreateLogGroup",
      "logs:DeleteLogGroup",
      "logs:DeleteRetentionPolicy",
      "logs:ListTagsForResource",
      "logs:ListTagsLogGroup",
      "logs:PutRetentionPolicy",
      "logs:TagLogGroup",
      "logs:TagResource",
      "logs:UntagLogGroup",
      "logs:UntagResource"
    ]
    resources = [
      local.test_log_group_arn,
      "arn:${data.aws_partition.current.partition}:logs:${var.aws_region}:${data.aws_caller_identity.current.account_id}:log-group:/ecs/${var.project_name}-test/backend"
    ]
  }
}

resource "aws_iam_role_policy" "github_test_foundation" {
  name   = "terraform-test-foundation"
  role   = aws_iam_role.github_test.id
  policy = data.aws_iam_policy_document.github_test_foundation.json
}


