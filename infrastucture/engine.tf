
variable "vpc_id" { type = string }
variable "subnet_ids" { type = list(string) }
variable "security_group_ids" { type = list(string) }

resource "aws_s3_bucket" "data_upload_bucket" {
  bucket = "customer-data-upload-2025-prod"
}


resource "aws_iam_role" "lambda_exec_role" {
  name = "lambda-exec-role-scoring-engine"
  assume_role_policy = jsonencode({
    Version = "2012-10-17",
    Statement = [{
      Action = "sts:AssumeRole",
      Effect = "Allow",
      Principal = {
        Service = "lambda.amazonaws.com"
      }
    }]
  })
}

resource "aws_iam_role_policy" "lambda_policy" {
  name = "lambda-policy-access-control" # Renamed for clarity
  role = aws_iam_role.lambda_exec_role.id
  policy = jsonencode({
    Version = "2012-10-17",
    Statement = [
      {
        Action = [
          "logs:*"
        ],
        Effect   = "Allow",
        Resource = "*"
      },
      {
        Action = [
          "ec2:CreateNetworkInterface",
          "ec2:DescribeNetworkInterfaces",
          "ec2:DeleteNetworkInterface",
          "ec2:AssignPrivateIpAddresses",
          "ec2:UnassignPrivateIpAddresses"
        ],
        Effect   = "Allow",
        Resource = "*"
      }
    ]
  })
}

resource "aws_lambda_function" "scoring_engine_lambda" {
  function_name    = "scoring-engine-lambda-prod"
  role             = aws_iam_role.lambda_exec_role.arn
  handler          = "scoring_engine_lambda.lambda_handler"
  runtime          = "python3.10"
  timeout          = 30

  environment {
    variables = {
      DB_HOST = "my-rds-instance.abcdefg.us-east-1.rds.amazonaws.com"
      DB_NAME = "customer_data"
      DB_USER = "segment_writer"
      # DB_SECRET_ARN = aws_secretsmanager_secret.rds_credentials.arn # Ideal for password
    }
  }
  
  vpc_config {
    subnet_ids         = var.subnet_ids
    security_group_ids = var.security_group_ids
  }
}

resource "aws_lambda_permission" "allow_s3" {
  statement_id  = "AllowExecutionFromS3"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.scoring_engine_lambda.function_name
  principal     = "s3.amazonaws.com"
  source_arn    = aws_s3_bucket.data_upload_bucket.arn
}

resource "aws_s3_bucket_notification" "s3_trigger" {
  bucket = aws_s3_bucket.data_upload_bucket.id
  lambda_function {
    lambda_function_arn = aws_lambda_function.scoring_engine_lambda.arn
    events              = ["s3:ObjectCreated:*"]
  }
}
