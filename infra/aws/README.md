# Put SentinelVoice on one AWS free-plan EC2 instance.

This is HTTP only, on one `t3.small`. It runs the web app, Java API, ML engine, Postgres, and Jenkins. It does not run Asterisk or Ollama. The LLM path is the mock provider so the box does not need a GPU.

New AWS free accounts (opened on or after 15 July 2025) get credits, not a year of unlimited micro instances. The account ends after 6 months or when the credits hit zero. **Stop the instance when you are not using it.**

## What you do once

1. Create an AWS **Free plan** account. Do not turn on the paid plan.
2. In the console, create an IAM user with the `AdministratorAccess` policy and an access key. Do not use the root access key.
3. On your PC install [AWS CLI v2](https://aws.amazon.com/cli/) and [Terraform](https://developer.hashicorp.com/terraform/install) 1.5+.
4. `aws configure` with that access key and region `ap-south-1`.
5. In EC2 → Key pairs (region Mumbai), create a key pair named `sentinelvoice` and keep the `.pem` file.
6. Push this repo to GitHub `main` **before** `terraform apply`. First boot clones `main`.
7. Find your public IP (`https://checkip.amazonaws.com`). Copy `terraform.tfvars.example` to `terraform.tfvars` and set `admin_cidr` to that IP with `/32`, and `key_name` to `sentinelvoice`.
8. From `infra/aws/terraform`:

```bash
terraform init
terraform apply
```

9. SSH in: `ssh -i sentinelvoice.pem ec2-user@<public_ip>`
10. Wait until Jenkins is up: `sudo docker logs -f sentinelvoice-jenkins` (first start installs plugins, a few minutes).
11. Read the password: `sudo cat /opt/sentinelvoice-secrets/jenkins.env`
12. Open `http://<public_ip>:8080`, log in as `admin`, open job **sentinelvoice-deploy**, click **Build Now**. The first build pulls images and can take 30–60 minutes on `t3.small`.
13. When the build is green, open `http://<public_ip>/`.

Later code changes: push to `main`, then click **Build Now** again.

## Stop spending credits

```bash
aws ec2 stop-instances --instance-ids <id from terraform output>
```

Start again with `aws ec2 start-instances`. A stop/start can change the public IP. Update `SENTINELVOICE_CORS_ORIGINS` in `/opt/sentinelvoice-secrets/app.env`, then run the Jenkins job again. Jenkins stays on port 8080, which is open only to `admin_cidr`. If your IP changes, update the security group.

## If the build is killed

The ML image includes PyTorch and needs RAM. Set `instance_type = "m7i-flex.large"` in `terraform.tfvars` and apply again. That size spends credits faster. Stop it the same day if you only needed it for the build.

## Session Manager (no SSH)

The instance role can use Systems Manager. Install the [Session Manager plugin](https://docs.aws.amazon.com/systems-manager/latest/userguide/session-manager-working-with-install-plugin.html), then:

```bash
aws ssm start-session --target <instance_id> --region ap-south-1
```

## Tear down

`terraform destroy` from `infra/aws/terraform`. That deletes the instance, the disk, and the database on that disk.
