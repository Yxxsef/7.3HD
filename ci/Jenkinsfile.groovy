pipeline {
  agent { label 'docker' }

  options {
    timestamps()
    buildDiscarder(logRotator(numToKeepStr: '20'))
  }

  environment {
    DOCKERHUB_REPO = 'yousxf/7.3hd'
    IMAGE          = "${DOCKERHUB_REPO}:${GIT_COMMIT}"
  }

  stages {

    stage('Build') {
      steps {
        powershell '''
          $ErrorActionPreference = "Stop"
          if (!(Test-Path build)) { New-Item -ItemType Directory build | Out-Null }
          docker build -t "$env:IMAGE" .
          docker image inspect "$env:IMAGE" --format "{{.Id}}" | Tee-Object -FilePath build\\image.txt
        '''
        archiveArtifacts artifacts: 'build/**', fingerprint: true
      }
    }

    stage('Push') {
  steps {
    withCredentials([usernamePassword(
      credentialsId: 'dockerhub-creds',
      usernameVariable: 'DH_USER',
      passwordVariable: 'DH_PASS'
    )]) {
      powershell '''
        $ErrorActionPreference = "Stop"

        # Use a local docker config so we don't hit the Desktop credStore
        $env:DOCKER_CONFIG = Join-Path $PWD ".docker"
        New-Item -ItemType Directory -Force -Path $env:DOCKER_CONFIG | Out-Null
        '{"auths":{}}' | Out-File -Encoding ascii -FilePath (Join-Path $env:DOCKER_CONFIG "config.json")

        # Make sure we’re on Desktop’s engine
        docker context use desktop-linux | Out-Null

        # Login with your Docker Hub PAT
        $env:DH_PASS | docker login --username $env:DH_USER --password-stdin docker.io

        # Tag and push both the commit tag and 'latest'
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
      $ErrorActionPreference = "Stop"
      $work = (Get-Location).Path

      docker run --rm `
        -e PYTHONPATH=/app `
        -v "${work}:/app" -w /app python:3.11-slim sh -lc "
          python -m pip install --upgrade pip &&
          pip install -r requirements.txt &&
          pip install pytest pytest-cov &&
          mkdir -p reports &&
          python -m pytest -q --junitxml=reports/junit.xml \
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
    withSonarQubeEnv('sonar') {
      script {
        def scannerHome = tool 'sonar-scanner'
        bat """
          "${scannerHome}\\bin\\sonar-scanner.bat" ^
            -Dsonar.projectKey=Yxxsef_7.3HD ^
            -Dsonar.organization=yxxsef ^
            -Dsonar.sources=app ^
            -Dsonar.python.coverage.reportPaths=coverage.xml
        """
      }
    }
  }
}





stage('Security') {
  environment { SNYK_TOKEN = credentials('SNYK_TOKEN') }
  steps {
    powershell '''
      docker run --rm `
        -e SNYK_TOKEN=$env:SNYK_TOKEN `
        -v "${env:WORKSPACE}:/project" -w /project `
        --entrypoint sh snyk/snyk:docker `
        -lc "apk add --no-cache python3 py3-pip >/dev/null && \
             snyk test --package-manager=pip --file=requirements.txt --severity-threshold=high"
    '''
  }
}






  }
}
