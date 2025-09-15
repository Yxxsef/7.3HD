pipeline {
  agent { label 'docker' }          // Windows node with Docker Desktop
  tools { nodejs 'NodeLTS' }
  options {
    timestamps()
    buildDiscarder(logRotator(numToKeepStr: '20'))
    skipDefaultCheckout(true)
  }

  stages {
    stage('Checkout') { steps { checkout scm } }

    stage('Build') {
      steps { script { writeFile file: 'dist/artifact.txt', text: 'build\n' } }
    }

    stage('Warm cache') {
      steps {
        script {
          if (isUnix()) {
            sh '''
              WS="$WORKSPACE"
              docker pull python:3.11-slim

              docker run --rm -v "$WORKSPACE/.pip-cache:/root/.cache/pip" -v "$WS:/src" -w /src \
                python:3.11-slim sh -lc "python -m pip install -U pip || true"

              docker run --rm -v "$WORKSPACE/.pip-cache:/root/.cache/pip" -v "$WS:/src" -w /src \
                python:3.11-slim sh -lc "pip download -d /root/.cache/pip -r requirements-dev.txt || true"

              docker run --rm -v "$WORKSPACE/.pip-cache:/root/.cache/pip" -v "$WS:/src" -w /src \
                python:3.11-slim sh -lc "pip download -d /root/.cache/pip -r requirements.txt || true"

              docker run --rm -v "$WORKSPACE/.pip-cache:/root/.cache/pip" -v "$WS:/src" -w /src \
                python:3.11-slim sh -lc "pip download -d /root/.cache/pip pytest pytest-cov coverage || true"
            '''
          } else {
            bat '''
              set "WS=%WORKSPACE:\\=/%"
              docker pull python:3.11-slim

              rem mount cache + workspace so requirements*.txt are visible
              docker run --rm -v "%WORKSPACE%\\.pip-cache:/root/.cache/pip" -v "%WS%:/src" -w /src ^
                python:3.11-slim sh -lc "python -m pip install -U pip || true"

              docker run --rm -v "%WORKSPACE%\\.pip-cache:/root/.cache/pip" -v "%WS%:/src" -w /src ^
                python:3.11-slim sh -lc "pip download -d /root/.cache/pip -r requirements-dev.txt || true"

              docker run --rm -v "%WORKSPACE%\\.pip-cache:/root/.cache/pip" -v "%WS%:/src" -w /src ^
                python:3.11-slim sh -lc "pip download -d /root/.cache/pip -r requirements.txt || true"

              docker run --rm -v "%WORKSPACE%\\.pip-cache:/root/.cache/pip" -v "%WS%:/src" -w /src ^
                python:3.11-slim sh -lc "pip download -d /root/.cache/pip pytest pytest-cov coverage || true"
            '''
          }
        }
      }
    }

    stage('Test') {
      options { timeout(time: 20, unit: 'MINUTES') }
      steps {
        script {
          if (isUnix()) {
            sh '''
              WS="$WORKSPACE"
              docker run --rm -u root \
                -v "$WORKSPACE/.pip-cache:/root/.cache/pip" \
                -v "$WS:/src" -w /src \
                python:3.11-slim sh -lc 'python -V && \
                  python -m pip install -U pip && \
                  if [ -f requirements-dev.txt ]; then pip install --prefer-binary -r requirements-dev.txt; fi && \
                  if [ -f requirements.txt ]; then pip install --prefer-binary -r requirements.txt; fi && \
                  pip install --prefer-binary -U pytest pytest-cov coverage && \
                  mkdir -p reports && \
                  pytest -q --junitxml=reports/junit.xml --cov=. \
                         --cov-report=xml:reports/coverage.xml --cov-report=term; \
                  rc=$?; coverage html -d reports/html || true; exit $rc'
            '''
          } else {
            bat '''
              setlocal EnableExtensions EnableDelayedExpansion
              set "WS=%WORKSPACE:\\=/%"
              docker run --rm -u root ^
                -v "%WORKSPACE%\\.pip-cache:/root/.cache/pip" ^
                -v "%WS%:/src" -w /src ^
                python:3.11-slim sh -lc "python -V && \
                  python -m pip install -U pip && \
                  if [ -f requirements-dev.txt ]; then pip install --prefer-binary -r requirements-dev.txt; fi && \
                  if [ -f requirements.txt ]; then pip install --prefer-binary -r requirements.txt; fi && \
                  pip install --prefer-binary -U pytest pytest-cov coverage && \
                  mkdir -p reports && \
                  pytest -q --junitxml=reports/junit.xml --cov=. --cov-report=xml:reports/coverage.xml --cov-report=term; \
                  rc=$?; coverage html -d reports/html || true; exit $rc"
            '''
          }
        }
      }
      post {
        always {
          junit allowEmptyResults: true, testResults: 'reports/junit.xml'
          publishHTML(
            allowMissing: true,
            alwaysLinkToLastBuild: true,
            keepAll: false,
            reportDir: 'reports/html',
            reportFiles: 'index.html',
            reportName: 'Coverage HTML'
          )
          archiveArtifacts artifacts: 'reports/**', fingerprint: true
        }
      }
    }

    stage('Sonar') {
      environment { SCANNER_HOME = tool 'SonarScanner' }
      steps {
        withSonarQubeEnv('7.3HD') {
          script {
            if (isUnix()) {
              sh '"$SCANNER_HOME/bin/sonar-scanner" -Dsonar.organization=yxxsef -Dsonar.projectKey=yxxsef_7-3hd -Dsonar.projectName=7.3HD -Dsonar.sources=app -Dsonar.tests=tests -Dsonar.python.coverage.reportPaths=reports/coverage.xml -Dsonar.junit.reportPaths=reports/junit.xml -Dsonar.exclusions=node_modules/**,dist/**,**/*.test.js,**/*.spec.js"'
            } else {
              bat '"%SCANNER_HOME%\\bin\\sonar-scanner.bat" -Dsonar.organization=yxxsef -Dsonar.projectKey=yxxsef_7-3hd -Dsonar.projectName=7.3HD -Dsonar.sources=app -Dsonar.tests=tests -Dsonar.python.coverage.reportPaths=reports/coverage.xml -Dsonar.junit.reportPaths=reports/junit.xml -Dsonar.exclusions=node_modules/**,dist/**,**/*.test.js,**/*.spec.js"'
            }
          }
        }
      }
    }

    stage('Env check') {
      steps {
        script {
          if (isUnix()) sh 'node -v || true; docker --version || true'
          else          bat 'node -v || ver >NUL & docker --version || ver >NUL'
        }
      }
    }

    stage('Package') {
      steps { script { writeFile file: 'dist/ok.txt', text: 'ok\n' } }
    }
  }

  post {
    always { archiveArtifacts artifacts: 'dist/**', allowEmptyArchive: true }
  }
}
