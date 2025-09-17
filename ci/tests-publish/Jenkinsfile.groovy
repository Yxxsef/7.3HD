pipeline {
  agent { label 'docker-agent' }
  options { timestamps() }

  environment {
    PY_IMAGE = 'python:3.11-slim'
  }

  stages {
    stage('Test') {
      steps {
        powershell '''
          $ErrorActionPreference = "Stop"
          $ws = $env:WORKSPACE
          docker run --rm -v "$ws:/app" -w /app $env:PY_IMAGE /bin/sh -lc "
            python -m pip install --upgrade pip &&
            pip install --no-cache-dir -r requirements.txt &&
            mkdir -p reports &&
            pytest -q --cov=app --cov-report=xml --cov-report=html:htmlcov --junitxml=reports/junit.xml
          "
        '''
      }
    }

    stage('Publish reports') {
      steps {
        junit 'reports/junit.xml'
        publishHTML target: [
          reportDir: 'htmlcov',
          reportFiles: 'index.html',
          reportName: 'Coverage',
          keepAll: true,
          allowMissing: true
        ]
        archiveArtifacts artifacts: 'reports/*.xml, htmlcov/**', fingerprint: true, allowEmptyArchive: true
      }
    }
  }

  post {
    always { cleanWs() }
  }
}
