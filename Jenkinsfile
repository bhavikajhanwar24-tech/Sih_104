// Used if you point a Jenkins job at this repo. The EC2 controller job calls the same script.
pipeline {
  agent any
  options {
    timestamps()
    disableConcurrentBuilds()
  }
  stages {
    stage('Deploy') {
      steps {
        sh 'bash /opt/sentinelvoice/infra/aws/deploy.sh'
      }
    }
  }
}
