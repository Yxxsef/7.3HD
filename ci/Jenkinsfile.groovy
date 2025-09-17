pipeline {
  agent { label 'docker' }

  options {
    timestamps()
    buildDiscarder(logRotator(numToKeepStr: '20'))
  }

environment {
  DOCKERHUB_REPO = 'yousxf/7.3hd'
  IMAGE = "${DOCKERHUB_REPO}:${GIT_COMMIT}"
}


  stages {

    stage('Build') {
      steps {
        powershell '''
          $ErrorActionPreference = "Stop"
          if (!(Test-Path build)) { New-Item -ItemType Directory build | Out-Null }
          docker build -t "$env:IMAGE" .
          # capture the image ID/digest-ish info for evidence
          docker image inspect "$env:IMAGE" --format "{{.Id}}" | Tee-Object -FilePath build\\image.txt
        '''
        archiveArtifacts artifacts: 'build/**', fingerprint: true
      }
    }
    stage('Push') {
  steps {
    withCredentials([usernamePassword(credentialsId: 'dockerhub-creds',
                                      usernameVariable: 'DH_USER',
                                      passwordVariable: 'DH_PASS')]) {
      powershell '''
        $ErrorActionPreference = "Stop"
        docker logout 2>$null | Out-Null

        # safer password-stdin on Windows
        $p = Start-Process -FilePath docker -ArgumentList @("login","-u",$env:DH_USER,"--password-stdin") `
             -PassThru -NoNewWindow -RedirectStandardInput "STDIN"
        $sw = New-Object System.IO.StreamWriter($p.StandardInput.BaseStream)
        $sw.Write($env:DH_PASS); $sw.Close(); $p.WaitForExit()
        if ($p.ExitCode -ne 0) { throw "Docker login failed for $env:DH_USER" }

        docker info | Select-String '^ Username'
        docker push "$env:IMAGE"
      '''
    }
  }
}


    stage('Test') {
      steps {
        powershell '''
          $ErrorActionPreference = "Stop"
          # run tests inside a clean container, mounting the workspace
          docker run --rm -v "$PWD":/app -w /app python:3.11-slim sh -lc "
            python -m pip install --upgrade pip &&
            pip install -r requirements.txt &&
            pip install pytest pytest-cov &&
            mkdir -p reports &&
            pytest -q --junitxml=reports/junit.xml \
                   --cov=app \
                   --cov-report=xml:coverage.xml \
                   --cov-report=html:htmlcov \
                   --cov-fail-under=80
          "
        '''
        junit 'reports/junit.xml'
        publishHTML target: [
          reportName: 'Coverage',
          reportDir: 'htmlcov',
          reportFiles: 'index.html',
          keepAll: true,
          alwaysLinkToLastBuild: true,
          allowMissing: true
        ]
      }
    }

    stage('Code Quality (Sonar)') {
      steps {
        withSonarQubeEnv('7.3HD') { // <— use the exact name you configured in Manage Jenkins → System
          powershell '''
            $ErrorActionPreference = "Stop"
            # use Dockerized sonar-scanner; mount workspace at /usr/src
            docker run --rm `
              -e SONAR_HOST_URL=$env:SONAR_HOST_URL `
              -e SONAR_LOGIN=$env:SONAR_AUTH_TOKEN `
              -v "$PWD":/usr/src `
              sonarsource/sonar-scanner-cli `
              -Dsonar.python.coverage.reportPaths=coverage.xml
          '''
        }
        // Abort build if Quality Gate fails (gives you the evidence for submission)
        timeout(time: 3, unit: 'MINUTES') {
          waitForQualityGate abortPipeline: true
        }
      }
    }

    stage('Security') {
      steps {
        withCredentials([string(credentialsId: 'snyk-token', variable: 'SNYK_TOKEN')]) {
          powershell '''
            $ErrorActionPreference = "Stop"
            if (!(Test-Path reports)) { New-Item -ItemType Directory reports | Out-Null }

            # Snyk (code/dependencies)
            docker run --rm -e SNYK_TOKEN="$env:SNYK_TOKEN" -v "$PWD":/project -w /project snyk/snyk:stable `
              test --severity-threshold=high --json `
              | Tee-Object -FilePath reports\\snyk.json
            $snykExit = $LASTEXITCODE

            # Trivy (container image) — fail on HIGH/CRITICAL
            docker run --rm aquasec/trivy:latest image `
              --severity HIGH,CRITICAL --exit-code 1 --no-progress "$env:IMAGE" `
              | Tee-Object -FilePath reports\\trivy.txt
            $trivyExit = $LASTEXITCODE

            if ($snykExit -ne 0 -or $trivyExit -ne 0) { exit 1 }
          '''
        }
        archiveArtifacts artifacts: 'reports/snyk.json, reports/trivy.txt', fingerprint: true, onlyIfSuccessful: false
      }
    }
  } // stages

  post {
    success {
      withCredentials([string(credentialsId: 'slack-token', variable: 'SLACK_WEBHOOK')]) {
        powershell '''
          $payload = @{ text = "✅ Passed: $env:JOB_NAME #$env:BUILD_NUMBER`n$env:BUILD_URL" } | ConvertTo-Json
          Invoke-RestMethod -Uri $env:SLACK_WEBHOOK -Method Post -ContentType "application/json" -Body $payload | Out-Null
        '''
      }
    }
    failure {
      withCredentials([string(credentialsId: 'slack-token', variable: 'SLACK_WEBHOOK')]) {
        powershell '''
          $payload = @{ text = "❌ Failed: $env:JOB_NAME #$env:BUILD_NUMBER`n$env:BUILD_URL" } | ConvertTo-Json
          Invoke-RestMethod -Uri $env:SLACK_WEBHOOK -Method Post -ContentType "application/json" -Body $payload | Out-Null
        '''
      }
    }
  }
}
