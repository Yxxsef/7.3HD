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
    SONAR_INSTANCE = 'sonar'
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
            echo === DEBUG VARS ===
            echo GIT_COMMIT=%GIT_COMMIT%
            echo IMAGE=%IMAGE%
            echo DOCKERHUB_REPO=%DOCKERHUB_REPO%
            echo DOCKER_CRED_ID=%DOCKER_CRED_ID%
            echo ====================
            docker images | findstr 7.3hd
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
    withSonarQubeEnv('sonarqube-local') {
      script {
        def scannerHome = tool 'sonar-scanner'
        bat "\"${scannerHome}\\bin\\sonar-scanner.bat\" " +
            "-Dsonar.projectKey=7.3HD " +
            "-Dsonar.projectVersion=${GIT_COMMIT}"
      }
    }
  }
}

stage('Quality Gate') {
  steps {
    timeout(time: 10, unit: 'MINUTES') {
      waitForQualityGate abortPipeline: true
    }
  }
}



stage('Security') {
  parallel {
    stage('snyk') {
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
      post { always { archiveArtifacts artifacts: 'reports/*.json', fingerprint: true } }
    }

    stage('deps (pip-audit)') {
      steps {
        bat '''
          docker run --rm -v "%WORKSPACE%:/src" -w /src python:3.11-slim sh -lc ^
            "python -m pip install -U pip pip-audit && pip-audit -r requirements.txt --strict"
        '''
      }
    }

    stage('trivy fs+image') {
      steps {
        bat '''
          docker run --rm -v "%WORKSPACE%:/src" -v trivy-cache:/root/.cache/ aquasec/trivy:latest ^
            fs --severity HIGH,CRITICAL --exit-code 1 --format json --output /src\\trivy-fs.json /src
        '''
        bat '''
          docker run --rm -v trivy-cache:/root/.cache/ aquasec/trivy:latest ^
            image --severity HIGH,CRITICAL --exit-code 1 --format json --output trivy-image.json %IMAGE%
        '''
      }
      post { always { archiveArtifacts artifacts: 'trivy-fs.json,trivy-image.json', allowEmptyArchive: true } }
    }

    stage('semgrep (SAST)') {
      steps {
        bat '''
          docker run --rm -v "%WORKSPACE%:/src" returntocorp/semgrep:latest ^
            semgrep --config p/ci --error --metrics=off /src
        '''
      }
    }

    stage('secrets (gitleaks)') {
      steps {
        bat '''
          docker run --rm -v "%WORKSPACE%:/repo" zricethezav/gitleaks:latest ^
            detect --no-git --redact -v --exit-code 1 --source=/repo --report-path gitleaks.json
        '''
      }
      post { always { archiveArtifacts artifacts: 'gitleaks.json', fingerprint: true } }
    }
  }
}





stage('Deploy (staging)') {
  when { branch 'main' }
  steps {
    withCredentials([usernamePassword(credentialsId: env.DOCKER_CRED_ID, usernameVariable: 'DH_USER', passwordVariable: 'DH_PASS')]) {
      bat '''
        docker context use desktop-linux
        echo %DH_PASS% | docker login -u %DH_USER% --password-stdin
        docker pull %DOCKERHUB_REPO%:staging
        docker compose -f docker-compose.staging.yml down -v || echo no stack
        docker compose -f docker-compose.staging.yml up -d --pull always --remove-orphans
        docker compose -f docker-compose.staging.yml ps
        docker logout
      '''
    }
  }
  post {
    failure {
      bat 'docker logs --tail=500 hd-staging > deploy-fail-logs.txt || echo no logs'
      archiveArtifacts artifacts: 'deploy-fail-logs.txt', fingerprint: true
    }
  }
}

stage('Smoke test (staging)') {
  steps {
    powershell '''
      $ErrorActionPreference="Stop"
      $deadline = (Get-Date).AddMinutes(2)
      do {
        Start-Sleep 3
        $h = docker inspect --format "{{.State.Health.Status}}" hd-staging 2>$null
      } until ($h -eq "healthy" -or (Get-Date) -gt $deadline)
      if ($h -ne "healthy") { throw "Service never became healthy" }

      $r = Invoke-WebRequest http://localhost:8000/metrics -TimeoutSec 5 -UseBasicParsing
      if ($r.StatusCode -ne 200) { throw "metrics returned $($r.StatusCode)" }
      $r.Content | Out-File -Encoding utf8 metrics.txt

      try {
        (Invoke-RestMethod http://localhost:8000/health -TimeoutSec 5) | Out-String | Tee-Object smoke-health.txt | Out-Null
      } catch { "no /health endpoint; metrics OK" | Tee-Object smoke-health.txt | Out-Null }

      docker inspect hd-staging > inspect.json
    '''
    archiveArtifacts artifacts: 'metrics.txt,smoke-health.txt,inspect.json', fingerprint: true
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
