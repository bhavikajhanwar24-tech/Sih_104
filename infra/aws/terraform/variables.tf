variable "aws_region" {
  description = "Region close to you. ap-south-1 is Mumbai."
  type        = string
  default     = "ap-south-1"
}

variable "instance_type" {
  description = "Free-plan eligible x86 type. t3.small is the smallest that can build this app. Use m7i-flex.large if the Docker build is killed."
  type        = string
  default     = "t3.small"

  validation {
    condition = contains([
      "t2.micro",
      "t3.micro",
      "t3.small",
      "c7i-flex.large",
      "m7i-flex.large",
    ], var.instance_type)
    error_message = "Use t3.small (default) or m7i-flex.large. ARM types are not listed because the images are amd64."
  }
}

variable "admin_cidr" {
  description = "Your current public IP with /32. SSH and Jenkins allow only this address."
  type        = string

  validation {
    condition     = var.admin_cidr != "0.0.0.0/0" && can(cidrhost(var.admin_cidr, 0))
    error_message = "Set your own public IP, for example 203.0.113.10/32. Do not use 0.0.0.0/0."
  }
}

variable "key_name" {
  description = "Existing EC2 key pair name. Leave empty to use SSM Session Manager only."
  type        = string
  default     = ""
}

variable "repo_url" {
  description = "Git URL cloned onto the instance at first boot."
  type        = string
  default     = "https://github.com/bhavikajhanwar24-tech/Sih_104.git"
}

variable "repo_branch" {
  description = "Branch cloned at first boot. Later deploys always pull origin/main."
  type        = string
  default     = "main"
}
