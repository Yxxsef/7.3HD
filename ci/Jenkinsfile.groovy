pipeline {
  agent any
  stages {
    stage('Checkout'){ steps { checkout scm } }
    stage('Ping'){ steps { echo "Hook OK on ${env.NODE_NAME}. SHA=${env.GIT_COMMIT?.take(7)}" } }
  }
}
