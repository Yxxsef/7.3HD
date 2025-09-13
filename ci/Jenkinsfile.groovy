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
    stage('Test'){  steps { sh 'echo test' } }
    stage('Sonar') {
      environment { SCANNER_HOME = tool 'SonarScanner' }
      steps {
        withSonarQubeEnv('7.3HD') {
          sh """
            ${SCANNER_HOME}/bin/sonar-scanner \
              -Dsonar.projectBaseDir=. \
              -Dsonar.sources=. \
              -Dsonar.exclusions=node_modules/**,dist/**,**/*.test.js,**/*.spec.js
          """
        }
      }
    }
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