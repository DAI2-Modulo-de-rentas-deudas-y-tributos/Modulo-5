locals {
  prod_ecr_repository_arn = "arn:${data.aws_partition.current.partition}:ecr:${var.aws_region}:${data.aws_caller_identity.current.account_id}:repository/${var.project_name}-prod-backend"
  prod_ecs_cluster_arn    = "arn:${data.aws_partition.current.partition}:ecs:${var.aws_region}:${data.aws_caller_identity.current.account_id}:cluster/${var.project_name}-prod-cluster"
  prod_log_group_arn      = "arn:${data.aws_partition.current.partition}:logs:${var.aws_region}:${data.aws_caller_identity.current.account_id}:log-group:/ecs/${var.project_name}-prod/backend:*"
}

data "aws_iam_policy_document" "github_prod_foundation" {
  statement {
    sid = "ReadNetworkState"
    actions = [
      "ec2:Describe*",
      "ec2:GetManagedPrefixListEntries",
      "ec2:GetSecurityGroupsForVpc"
    ]
    resources = ["*"]
  }

  statement {
    sid = "ManageProdNetworkFoundation"
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
    sid = "ManageProdBackendRepository"
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
    resources = [local.prod_ecr_repository_arn]
  }

  statement {
    sid = "ManageProdEcsCluster"
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
    resources = [local.prod_ecs_cluster_arn]
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
    sid       = "ReadProdLogGroups"
    actions   = ["logs:DescribeLogGroups"]
    resources = ["*"]
  }

  statement {
    sid = "ManageProdBackendLogGroup"
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
      local.prod_log_group_arn,
      "arn:${data.aws_partition.current.partition}:logs:${var.aws_region}:${data.aws_caller_identity.current.account_id}:log-group:/ecs/${var.project_name}-prod/backend"
    ]
  }
}

resource "aws_iam_role_policy" "github_prod_foundation" {
  name   = "terraform-prod-foundation"
  role   = aws_iam_role.github_prod.id
  policy = data.aws_iam_policy_document.github_prod_foundation.json
}
