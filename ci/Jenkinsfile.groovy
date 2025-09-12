pipeline {
  agent any
  options { timestamps() }
  stages {
    stage('Checkout'){ steps { checkout scm } }
    stage('Build'){ steps { sh 'echo OK' } }
  }
}