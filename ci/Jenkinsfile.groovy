pipeline {
  agent { label "docker" }
  options { timestamps() }

  stages {
    stage("Checkout") {
      steps { checkout scm }
    }

    stage("Build") {
      steps { sh "echo build" }
    }

    stage("Test") {
      agent {
        docker { image "python:3.11-slim"; args "-v $WORKSPACE:$WORKSPACE -w $WORKSPACE" }
      }
      steps {
        sh '''
          python -m pip install -U pip
          pip install -r requirements.txt pytest pytest-cov
          export PYTHONPATH="$WORKSPACE"
          pytest -q --cov=app --cov-report=xml:coverage.xml --cov-fail-under=80 \
                 --junitxml=pytest-junit.xml
        '''
      }
      post {
        always {
          junit 'pytest-junit.xml'
          publishCoverage adapters: [coberturaAdapter('coverage.xml')]
          archiveArtifacts artifacts: 'coverage.xml,pytest-junit.xml', allowEmptyArchive: true
        }
      }
    }

    stage("Sonar") {
      steps {
        withSonarQubeEnv("7.3HD") {
          sh "${tool 'SonarScanner'}/bin/sonar-scanner \
            -Dsonar.organization=yxxsef \
            -Dsonar.projectKey=yxxsef_7-3hd \
            -Dsonar.projectName=7.3HD \
            -Dsonar.sources=app \
            -Dsonar.tests=tests \
            -Dsonar.python.coverage.reportPaths=coverage.xml \
            -Dsonar.exclusions=node_modules/**,dist/**,**/*.test.js,**/*.spec.js"
        }
      }
    }

    stage("Package") {
      steps { sh "mkdir -p dist && echo build > dist/ok.txt" }
    }
  }

  post {
    success { archiveArtifacts artifacts: "dist/**", allowEmptyArchive: true }
  }
}
