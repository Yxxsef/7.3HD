pipeline {
  agent { label 'docker' }
  tools { nodejs 'NodeLTS' } // keep if you already configured Node
  options {
    timestamps()
    buildDiscarder(logRotator(numToKeepStr: '20'))
    skipDefaultCheckout(true)
  }

  stages {
    stage('Checkout'){ steps { checkout scm } }

    stage('Build'){ steps { sh 'echo build' } }

    stage('Test') {
      agent { docker { image 'python:3.11-slim'; args '-u root' } }
      environment {
        PIP_CACHE_DIR = '.pip-cache'
        PYTHONDONTWRITEBYTECODE = '1'
      }
      steps {
        sh '''
          python -V
          python -m pip install --upgrade pip
          if [ -f requirements-dev.txt ]; then
            pip install -r requirements-dev.txt
          else
            pip install -r requirements.txt || true
            pip install pytest pytest-cov coverage
          fi

          mkdir -p reports
          pytest -q --maxfail=1 --disable-warnings \
            --junitxml=reports/junit.xml \
            --cov=app --cov-report=xml:reports/coverage.xml --cov-report=term
          coverage html -d reports/html || true
        '''
      }
      post {
        always {
          junit testResults: 'reports/junit.xml', allowEmptyResults: true
          script {
            try {
              publishCoverage adapters: [coberturaAdapter('reports/coverage.xml')]
            } catch (e) {
              echo 'Coverage plugin missing; publishing HTML instead.'
              publishHTML([reportDir: 'reports/html', reportFiles: 'index.html', reportName: 'Coverage HTML'])
            }
          }
          archiveArtifacts artifacts: 'reports/**', fingerprint: true
        }
      }
    }

    stage('Sonar') {
      environment { SCANNER_HOME = tool 'SonarScanner' }
      steps {
        withSonarQubeEnv('7.3HD') {
          sh '''
            "$SCANNER_HOME/bin/sonar-scanner" \
              -Dsonar.organization=yxxsef \
              -Dsonar.projectKey=yxxsef_7-3hd \
              -Dsonar.projectName=7.3HD \
              -Dsonar.sources=app \
              -Dsonar.tests=tests \
              -Dsonar.python.coverage.reportPaths=reports/coverage.xml \
              -Dsonar.junit.reportPaths=reports/junit.xml \
              -Dsonar.exclusions=node_modules/**,dist/**,**/*.test.js,**/*.spec.js
          '''
        }
      }
    }

    stage('Env check'){ steps { sh 'node -v || true; docker --version || true' } }

    stage('Package'){ steps { sh 'mkdir -p dist && echo build > dist/artifact.txt' } }
  }

  post {
    always {
      archiveArtifacts artifacts: 'dist/**', allowEmptyArchive: true
    }
  }
}
