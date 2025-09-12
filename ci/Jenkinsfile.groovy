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
    stage('Package'){ steps { sh 'mkdir -p dist && echo build > dist/artifact.txt' } }
  }
  post {
    always {
      archiveArtifacts artifacts: 'dist/**,build/**',
                       fingerprint: true,
                       allowEmptyArchive: true
    }
  }
}