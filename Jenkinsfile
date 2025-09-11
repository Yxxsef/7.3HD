pipeline {
  agent any
  options { timestamps(); ansiColor('xterm') }
  stages {
    stage('Checkout') {
      steps { checkout scm }
    }
    stage('Build') {
      steps { sh 'docker build -t hd73:dev .' }
    }
    stage('Test') {
      steps {
        sh 'docker run --rm hd73:dev sh -lc "pytest -q && pytest --cov=app --cov-report=xml"'
      }
      post {
        always { archiveArtifacts artifacts: 'coverage.xml', allowEmptyArchive: true }
      }
    }
  }
}
