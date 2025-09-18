pipeline {
  agent { label 'docker' }
  options {
    timestamps()
    buildDiscarder(logRotator(numToKeepStr: '20'))
  }
  environment {
    DOCKERHUB_REPO   = 'yousxf/7.3hd'
    IMAGE            = "${DOCKERHUB_REPO}:${GIT_COMMIT}"
    COMPOSE_FILE     = 'docker-compose.staging.yml'
    SONAR_INSTANCE   = 'sonar'           // Jenkins Sonar server name
    SONAR_PROJECT    = 'Yxxsef_7.3HD'
    SONAR_ORG        = 'yxxsef'
  }

  stages {

    stage('Build') {
      steps {
        powershell '''
          $ErrorActionPreference="Stop"
          New-Item -ItemType Directory -Force -Path build | Out-Null
          docker context use desktop-linux | Out-Null
          docker build -t "$env:IMAGE" .
          docker image inspect "$env:IMAGE" --format "{{.Id}}" | Tee-Object build\\image.txt
        '''
        archiveArtifacts artifacts: 'build/**', fingerprint: true
      }
    }

    stage('Push') {
      steps {
        withCredentials([usernamePassword(credentialsId: 'dockerhub-creds', usernameVariable: 'DH_USER', passwordVariable: 'DH_PASS')]) {
          powershell '''
            $ErrorActionPreference="Stop"
            $env:DOCKER_CONFIG = Join-Path $PWD ".docker"
            New-Item -ItemType Directory -Force -Path $env:DOCKER_CONFIG | Out-Null
            '{"auths":{}}' | Out-File -Encoding ascii -FilePath (Join-Path $env:DOCKER_CONFIG "config.json")
            docker context use desktop-linux | Out-Null
            $env:DH_PASS | docker login --username $env:DH_USER --password-stdin docker.io
            docker tag "$env:IMAGE" "$env:DOCKERHUB_REPO:latest"
            docker push "$env:IMAGE"
            docker push "$env:DOCKERHUB_REPO:latest"
            docker logout docker.io
          '''
        }
      }
    }

    stage('Test') {
      steps {
        powershell '''
          $ErrorActionPreference="Stop"
          $work=(Get-Location).Path
          docker run --rm -e PYTHONPATH=/app -v "${work}:/app" -w /app python:3.11-slim sh -lc "
            python -m pip install --upgrade pip &&
            pip install -r requirements.txt &&
            pip install pytest pytest-cov &&
            mkdir -p reports &&
            pytest -q --junitxml=reports/junit.xml \
                   --cov=app --cov-report=xml:coverage.xml \
                   --cov-report=html:htmlcov --cov-fail-under=80
          "
        '''
        junit 'reports/junit.xml'
        publishHTML target: [reportName: 'Coverage', reportDir: 'htmlcov', reportFiles: 'index.html', keepAll: true, alwaysLinkToLastBuild: true]
      }
    }

    stage('Code Quality (Sonar)') {
      steps {
        withSonarQubeEnv("${SONAR_INSTANCE}") {
          script {
            def scannerHome = tool 'sonar-scanner'
            bat """
              "${scannerHome}\\bin\\sonar-scanner.bat" ^
                -Dsonar.projectKey=${SONAR_PROJECT} ^
                -Dsonar.organization=${SONAR_ORG} ^
                -Dsonar.sources=app ^
                -Dsonar.python.coverage.reportPaths=coverage.xml
            """
          }
        }
      }
    }



    
stage('Quality Gate') {
  steps {
    timeout(time: 5, unit: 'MINUTES') {
      waitForQualityGate()  // aborts the build if the Quality Gate fails
    }
  }
}






    stage('Security') {
      steps {
        withCredentials([string(credentialsId: 'snyk-token', variable: 'SNYK_TOKEN')]) {
          powershell '''
            $ErrorActionPreference="Stop"
            mkdir reports -ea 0 | Out-Null
            snyk auth $env:SNYK_TOKEN
            snyk test --severity-threshold=high --json-file-output=reports\\snyk-deps.json
            snyk code test --severity-threshold=high --json-file-output=reports\\snyk-code.json
          '''
        }
      }
      post {
        always { archiveArtifacts artifacts: 'reports/*.json', fingerprint: true }
        failure { echo 'Security findings above threshold' }
      }
    }

    stage('Deploy (staging)') {
      when { branch 'main' }
      steps {
        withCredentials([usernamePassword(credentialsId: 'dockerhub-creds', usernameVariable: 'DH_USER', passwordVariable: 'DH_PASS')]) {
          powershell '''
            $ErrorActionPreference="Stop"
            docker context use desktop-linux | Out-Null
            $env:IMAGE_TAG=$env:GIT_COMMIT
            $env:DH_PASS | docker login --username $env:DH_USER --password-stdin docker.io
            docker compose -f $env:COMPOSE_FILE pull
            docker compose -f $env:COMPOSE_FILE up -d --remove-orphans
            curl -fsS http://localhost:8000/health > $env:WORKSPACE\\health.txt
            docker logout docker.io
          '''
        }
        archiveArtifacts artifacts: 'health.txt', fingerprint: true
      }
    }

    stage('Release') {
      when { branch 'main' }
      steps {
        input message: 'Promote to production?', ok: 'Release'
        powershell '''
          $ErrorActionPreference="Stop"
          docker pull ${env.IMAGE}
          docker tag ${env.IMAGE} ${env.DOCKERHUB_REPO}:${env.BUILD_NUMBER}
          docker tag ${env.IMAGE} ${env.DOCKERHUB_REPO}:prod
          docker push ${env.DOCKERHUB_REPO}:${env.BUILD_NUMBER}
          docker push ${env.DOCKERHUB_REPO}:prod
        '''
      }
    }

    stage('Monitoring (ping)') {
      steps {
        powershell 'curl -fsS http://localhost:8000/metrics > metrics.txt'
        archiveArtifacts artifacts: 'metrics.txt', fingerprint: true
      }
    }
  }
}
