output "public_ip" {
  value = aws_instance.this.public_ip
}

output "app_url" {
  value = "http://${aws_instance.this.public_ip}/"
}

output "jenkins_url" {
  value = "http://${aws_instance.this.public_ip}:8080/"
}

output "instance_id" {
  value = aws_instance.this.id
}

output "ssh" {
  value = var.key_name == "" ? "No key_name set. Use Session Manager." : "ssh -i <your.pem> ec2-user@${aws_instance.this.public_ip}"
}

output "ssm" {
  value = "aws ssm start-session --target ${aws_instance.this.id} --region ${var.aws_region}"
}
