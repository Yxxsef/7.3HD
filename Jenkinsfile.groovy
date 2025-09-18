pipeline {
  agent { label 'docker' }
  options {
    timestamps()
    buildDiscarder(logRotator(numToKeepStr: '20'))
  }
  environment {
    DOCKERHUB_REPO   = 'yousxf/7.3hd'
    DOCKER_CRED_ID = 'dockerhub-creds'
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
    withCredentials([usernamePassword(credentialsId: 'dockerhub-yousxf',
                                      usernameVariable: 'DH_USER',
                                      passwordVariable: 'DH_PASS')]) {
      bat '''
        docker context use desktop-linux
        echo IMAGE=%IMAGE%
        echo REPO=%DOCKERHUB_REPO%
        if "%IMAGE%"=="" exit /b 2
        if "%DOCKERHUB_REPO%"=="" exit /b 3

        echo %DH_PASS% | docker login -u %DH_USER% --password-stdin
        docker push %IMAGE%
        docker tag %IMAGE% %DOCKERHUB_REPO%:%BUILD_NUMBER%
        docker tag %IMAGE% %DOCKERHUB_REPO%:staging
        docker push %DOCKERHUB_REPO%:%BUILD_NUMBER%
        docker push %DOCKERHUB_REPO%:staging
        docker logout
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
    withSonarQubeEnv('sonar') {
      script {
        // Tool name must match what you created in Global Tool Configuration
        def scannerHome = tool name: 'sonar-scanner', type: 'hudson.plugins.sonar.SonarRunnerInstallation'
        bat "\"${scannerHome}\\bin\\sonar-scanner.bat\" " +
            "-Dsonar.projectKey=Yxxsef_7.3HD " +
            "-Dsonar.organization=yxxsef " +
            "-Dsonar.sources=app " +
            "-Dsonar.python.coverage.reportPaths=coverage.xml"
      }
    }
  }
}

stage('Quality Gate') {
  options { timeout(time: 1, unit: 'HOURS') }
  steps {
    script {
      def qg = waitForQualityGate(abortPipeline: false)
      echo "Quality Gate status: ${qg.status}"
      if (qg.status == 'ERROR') {
        error "Quality Gate failed: ${qg.status}"
      }
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
  steps {
    withCredentials([usernamePassword(
        credentialsId: 'dockerhub-yousxf',
        usernameVariable: 'DH_USER',
        passwordVariable: 'DH_PASS'
    )]) {
      bat '''
        docker context use desktop-linux
        echo %DH_PASS% | docker login -u %DH_USER% --password-stdin
        docker compose -f docker-compose.staging.yml pull
        docker compose -f docker-compose.staging.yml up -d
        docker logout
      '''
    }
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
