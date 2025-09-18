pipeline {
  agent { label 'docker' }
  options {
    timestamps()
    buildDiscarder(logRotator(numToKeepStr: '20'))
  }
  environment {
    DOCKERHUB_REPO = 'yousxf/7.3hd'
    DOCKER_CRED_ID = 'dockerhub-creds'
    IMAGE          = "${DOCKERHUB_REPO}:${GIT_COMMIT}"
    COMPOSE_FILE   = 'docker-compose.staging.yml'
    SONAR_INSTANCE = 'sonar'           // Jenkins Sonar server name
    SONAR_PROJECT  = 'Yxxsef_7.3HD'
    SONAR_ORG      = 'yxxsef'
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
        withCredentials([usernamePassword(credentialsId: env.DOCKER_CRED_ID, usernameVariable: 'DH_USER', passwordVariable: 'DH_PASS')]) {
          bat '''
            docker context use desktop-linux
            echo %DH_PASS% | docker login -u %DH_USER% --password-stdin

            REM ---- DEBUG: show the vars that build image refs ----
            echo === DEBUG VARS ===
            echo GIT_COMMIT=%GIT_COMMIT%
            echo IMAGE=%IMAGE%
            echo DOCKERHUB_REPO=%DOCKERHUB_REPO%
            echo DOCKER_CRED_ID=%DOCKER_CRED_ID%
            echo ====================
            docker images | findstr 7.3hd

            REM ---- push/tag ----
            docker push %IMAGE%
            docker tag %IMAGE% %DOCKERHUB_REPO%:staging
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
        def scannerHome = tool name: 'sonar-scanner', type: 'hudson.plugins.sonar.SonarRunnerInstallation'
        bat "\"${scannerHome}\\bin\\sonar-scanner.bat\" " +
            "-Dsonar.projectKey=Yxxsef_7.3HD " +
            "-Dsonar.organization=yxxsef " +
            "-Dsonar.sources=app " +
            "-Dsonar.python.coverage.reportPaths=coverage.xml " +
            "-Dsonar.projectVersion=%BUILD_NUMBER% " +
            "-Dsonar.branch.name=%BRANCH_NAME%"
        // capture ceTaskId for the next stage
        def rpt = readProperties file: '.scannerwork/report-task.txt'
        env.SONAR_CE_TASK_ID = rpt['ceTaskId']
      }
    }
  }
}

stage('Quality Gate') {
  options { timeout(time: 15, unit: 'MINUTES') }
  steps {
    withSonarQubeEnv('sonar') {
      powershell '''
        $ErrorActionPreference = "Stop"
        $auth = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("$env:SONAR_AUTH_TOKEN:"))
        $hdr  = @{ Authorization = "Basic $auth" }

        # wait for CE task to finish
        $deadline = (Get-Date).AddMinutes(10)
        do {
          Start-Sleep -Seconds 5
          $task = Invoke-RestMethod -Headers $hdr -Uri "https://sonarcloud.io/api/ce/task?id=$env:SONAR_CE_TASK_ID"
          $status = $task.task.status
          Write-Host "Sonar CE task status: $status"
        } until ($status -in @('SUCCESS','FAILED') -or (Get-Date) -gt $deadline)

        if ($status -eq 'FAILED') { throw "Sonar analysis failed (CE task FAILED)." }
        if ((Get-Date) -gt $deadline) { throw "Timed out waiting for Sonar analysis." }

        # get Quality Gate result for this analysis
        $analysisId = $task.task.analysisId
        $qg = Invoke-RestMethod -Headers $hdr -Uri "https://sonarcloud.io/api/qualitygates/project_status?analysisId=$analysisId"
        $gate = $qg.projectStatus.status
        Write-Host "Quality Gate status: $gate"
        if ($gate -ne 'OK') { throw "Quality Gate failed: $gate" }
      '''
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
        always  { archiveArtifacts artifacts: 'reports/*.json', fingerprint: true }
        failure { echo 'Security findings above threshold' }
      }
    }

    stage('Deploy (staging)') {
      when { branch 'main' }
      steps {
        withCredentials([usernamePassword(credentialsId: env.DOCKER_CRED_ID, usernameVariable: 'DH_USER', passwordVariable: 'DH_PASS')]) {
          bat '''
            docker context use desktop-linux
            echo %DH_PASS% | docker login -u %DH_USER% --password-stdin

            echo === DEPLOY DEBUG ===
            echo IMAGE=%IMAGE%
            echo DOCKERHUB_REPO=%DOCKERHUB_REPO%
            echo DOCKER_CRED_ID=%DOCKER_CRED_ID%
            echo =====================

            docker pull %DOCKERHUB_REPO%:staging
            REM your docker run/update here...
            docker logout
          '''
        }
      }
    }

    stage('Release') {
      when { branch 'main' }
      steps {
        withCredentials([usernamePassword(credentialsId: env.DOCKER_CRED_ID, usernameVariable: 'DH_USER', passwordVariable: 'DH_PASS')]) {
          bat '''
            docker context use desktop-linux
            echo %DH_PASS% | docker login -u %DH_USER% --password-stdin

            echo === RELEASE DEBUG ===
            echo IMAGE=%IMAGE%
            echo DOCKERHUB_REPO=%DOCKERHUB_REPO%
            echo ======================

            docker pull %IMAGE%
            docker tag %IMAGE% %DOCKERHUB_REPO%:prod
            docker push %DOCKERHUB_REPO%:prod
            docker logout
          '''
        }
      }
    }

    stage('Monitoring (ping)') {
      steps {
        bat 'curl -fsS http://localhost:8000/metrics > metrics.txt'
        archiveArtifacts artifacts: 'metrics.txt', fingerprint: true
      }
    }
  }
}
