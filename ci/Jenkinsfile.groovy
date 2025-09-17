pipeline {
  agent { label 'docker' }
  options { timestamps(); ansiColor('xterm'); buildDiscarder(logRotator(numToKeepStr: '20')) }
  environment {
    DOCKERHUB_REPO = 'yourDockerUser/fastapi-app'     // <— CHANGE ME
    IMAGE = "${DOCKERHUB_REPO}:${GIT_COMMIT}"
  }
  stages {

    stage('Build') {
      steps {
        sh '''
          set -eux
          mkdir -p build reports
          docker build -t "$IMAGE" .
          docker image inspect "$IMAGE" --format '{{.Id}}' | tee build/image.txt
        '''
        withCredentials([usernamePassword(credentialsId: 'dockerhub-creds', usernameVariable: 'DH_USER', passwordVariable: 'DH_PASS')]) {
          sh '''
            echo "$DH_PASS" | docker login -u "$DH_USER" --password-stdin
            docker push "$IMAGE"
          '''
        }
        archiveArtifacts artifacts: 'build/**', fingerprint: true
      }
    }

    stage('Test') {
      steps {
        sh '''
          set -eux
          docker run --rm -v "$PWD":/app -w /app python:3.11-slim bash -lc "
            pip install -r requirements.txt && pip install pytest pytest-cov &&
            mkdir -p reports &&
            pytest -q --junitxml=reports/junit.xml --cov=app --cov-report=xml:coverage.xml --cov-report=html:htmlcov --cov-fail-under=80
          "
        '''
        junit 'reports/junit.xml'
        publishHTML target: [reportName: 'Coverage', reportDir: 'htmlcov', reportFiles: 'index.html', keepAll: true, alwaysLinkToLastBuild: true, allowMissing: true]
      }
    }

    stage('Code Quality (Sonar)') {
      steps {
        withSonarQubeEnv('7.3HD') {   // <— your configured Sonar server name
          sh 'sonar-scanner -Dsonar.python.coverage.reportPaths=coverage.xml'
        }
      }
    }

    stage('Security') {
      steps {
        withCredentials([string(credentialsId: 'snyk-token', variable: 'SNYK_TOKEN')]) {
          sh '''
            set +e
            docker run --rm -e SNYK_TOKEN="$SNYK_TOKEN" -v "$PWD":/project -w /project snyk/snyk:stable test \
              --severity-threshold=high --json | tee reports/snyk.json
            SNYK_RC=${PIPESTATUS[0]}
            # Image scan (Trivy) — fails on HIGH/CRITICAL
            docker run --rm -v /var/run/docker.sock:/var/run/docker.sock aquasec/trivy:latest image \
              --severity HIGH,CRITICAL --exit-code 1 --no-progress "$IMAGE" | tee reports/trivy.txt
            TRIVY_RC=${PIPESTATUS[0]}
            exit $(( SNYK_RC || TRIVY_RC ))
          '''
        }
        archiveArtifacts artifacts: 'reports/snyk.json, reports/trivy.txt', fingerprint: true, onlyIfSuccessful: false
      }
    }
  }
  post {
    success {
      script { slackNotify("✅ Passed: ${env.JOB_NAME} #${env.BUILD_NUMBER}\\n${env.BUILD_URL}") }
    }
    failure {
      script { slackNotify("❌ Failed: ${env.JOB_NAME} #${env.BUILD_NUMBER}\\n${env.BUILD_URL}") }
    }
  }
}

def slackNotify(msg) {
  withCredentials([string(credentialsId: 'slack-token', variable: 'SLACK_WEBHOOK')]) {
    sh "curl -s -X POST -H 'Content-type: application/json' --data '{\"text\":\"${msg}\"}' \"$SLACK_WEBHOOK\" >/dev/null"
  }
}
