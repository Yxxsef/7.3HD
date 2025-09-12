pipeline {
  agent any
  options {
    timestamps()
    buildDiscarder(logRotator(numToKeepStr: '10'))
    skipDefaultCheckout(true)
  }
  stages {
    stage('Checkout'){ steps { checkout scm } }
    stage('Build'){ steps { sh 'echo build' } }
    stage('Test'){ steps { sh 'echo test' } }
  }
  post {
    always { archiveArtifacts artifacts: '**/dist/**, **/build/**', fingerprint: true }
  }
}