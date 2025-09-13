pipeline {
  agent { label 'docker' }
  tools { nodejs 'NodeLTS' }
  options { timestamps(); buildDiscarder(logRotator(numToKeepStr:'20')); skipDefaultCheckout(true) }

  stages {
    stage('Checkout'){ steps { checkout scm } }

    // No shell; works on Windows & Linux
    stage('Build'){ steps { script { writeFile file:'dist/artifact.txt', text:'build\n' } } }

    // Linux container -> OK to use sh here
    stage('Test') {
      agent { docker { image 'python:3.11-slim'; args '-u root' } }
      steps {
        sh '''
          python -V
          python -m pip install -U pip
          pip install -r requirements-dev.txt || pip install -r requirements.txt || true
          pip install pytest pytest-cov coverage
          mkdir -p reports
          pytest -q --junitxml=reports/junit.xml --cov=app \
                 --cov-report=xml:reports/coverage.xml --cov-report=term
          coverage html -d reports/html || true
        '''
      }
      post {
        always {
          junit 'reports/junit.xml'
          script {
            try { publishCoverage adapters: [coberturaAdapter('reports/coverage.xml')] }
            catch (e) { publishHTML([reportDir:'reports/html', reportFiles:'index.html', reportName:'Coverage HTML']) }
          }
          archiveArtifacts artifacts:'reports/**', fingerprint:true
        }
      }
    }

    stage('Sonar') {
      environment { SCANNER_HOME = tool 'SonarScanner' }
      steps {
        withSonarQubeEnv('7.3HD') {
          script {
            if (isUnix()) {
              sh '"$SCANNER_HOME/bin/sonar-scanner" -Dsonar.organization=yxxsef -Dsonar.projectKey=yxxsef_7-3hd -Dsonar.projectName=7.3HD -Dsonar.sources=app -Dsonar.tests=tests -Dsonar.python.coverage.reportPaths=reports/coverage.xml -Dsonar.junit.reportPaths=reports/junit.xml -Dsonar.exclusions=node_modules/**,dist/**,**/*.test.js,**/*.spec.js'
            } else {
              bat '"%SCANNER_HOME%\\bin\\sonar-scanner.bat" -Dsonar.organization=yxxsef -Dsonar.projectKey=yxxsef_7-3hd -Dsonar.projectName=7.3HD -Dsonar.sources=app -Dsonar.tests=tests -Dsonar.python.coverage.reportPaths=reports/coverage.xml -Dsonar.junit.reportPaths=reports/junit.xml -Dsonar.exclusions=node_modules/**,dist/**,**/*.test.js,**/*.spec.js'
            }
          }
        }
      }
    }

    stage('Env check'){
      steps {
        script {
          if (isUnix()) sh 'node -v || true; docker --version || true'
          else          bat 'node -v || ver>NUL & docker --version || ver>NUL'
        }
      }
    }

    // No shell; cross-platform
    stage('Package'){ steps { script { writeFile file:'dist/ok.txt', text:'ok\n' } } }
  }

  post { always { archiveArtifacts artifacts:'dist/**', allowEmptyArchive:true } }
}
