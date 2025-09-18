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
  options { timeout(time: 5, unit: 'MINUTES') }
  steps {
    withSonarQubeEnv('sonar') {
      powershell '''
$ErrorActionPreference = "Stop"

$base = "$env:SONAR_HOST_URL"            # https://sonarcloud.io
$taskId = "$env:SONAR_CE_TASK_ID"        # set in previous stage from report-task.txt
if (-not $taskId) { throw "SONAR_CE_TASK_ID is empty." }

Write-Host "Polling SonarCloud CE task $taskId ..."

# 1) Poll CE task (NO AUTH NEEDED for SonarCloud public projects)
$analysisId = $null
for ($i=0; $i -lt 180; $i++) {
  try {
    $t = Invoke-RestMethod -Method Get -Uri "$base/api/ce/task?id=$taskId"
  } catch {
    Start-Sleep -Seconds 2; continue  # transient 5xx—retry
  }
  $st = $t.task.status
  if ($st -eq "SUCCESS") { $analysisId = $t.task.analysisId; break }
  if ($st -eq "FAILED" -or $st -eq "CANCELED") {
    throw "Compute Engine status=$st (see $base/api/ce/task?id=$taskId)"
  }
  Start-Sleep -Seconds 2
}
if (-not $analysisId) { throw "Timed out waiting for analysis to finish." }

# 2) Fetch Quality Gate (needs auth). Build proper Basic header: <token> as username, blank password.
$pair = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("$($env:SONAR_AUTH_TOKEN):"))
$hdr  = @{ Authorization = "Basic $pair" }

$qg = Invoke-RestMethod -Method Get -Headers $hdr -Uri "$base/api/qualitygates/project_status?analysisId=$analysisId"
$status = $qg.projectStatus.status

Write-Host "Quality Gate: $status"
if ($status -ne "OK") {
  throw ("Quality Gate failed: " + $status + " - " + ($qg.projectStatus.conditions | ConvertTo-Json -Compress))
}
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
