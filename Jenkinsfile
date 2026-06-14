pipeline {
  agent { label 'docker-maven-trivy' }
  tools {
    maven 'maven3'
  }
  environment {
    SONAR_IP = '3.15.143.4'
    ECR_REGISTRY = '253685958295.dkr.ecr.us-east-2.amazonaws.com'
    IMAGE_REPO = "${ECR_REGISTRY}/zhoujie-devsecops-demo-2"
  }
  stages {
    stage('Trivy FS Scan') {
      steps {
        sh 'trivy fs --exit-code 1 --severity HIGH,CRITICAL .'
      }
    }
    stage('Build & Sonar') {
      steps {
        withCredentials([string(credentialsId: 'sonarqube-token', variable: 'SONAR_TOKEN')]) {
          sh 'mvn clean verify sonar:sonar \
            -Dsonar.projectKey=zhoujie-devsecops-demo \
            -Dsonar.host.url="http://${SONAR_IP}:9000" \
            -Dsonar.token="${SONAR_TOKEN}" \
            -Dsonar.qualitygate.wait=true'
        }
      }
    }
    stage('ECR Login') {
      steps {
        sh 'aws ecr get-login-password --region us-east-2 | docker login --username AWS --password-stdin $ECR_REGISTRY'
      }
    }
    stage('Build Image') {
      steps {
        sh 'export DOCKER_BUILDKIT=0 && docker build --platform linux/amd64 -t "$IMAGE_REPO:$BUILD_NUMBER" -t "$IMAGE_REPO:latest" .'
      }
    }
    stage('Trivy Image Scan') {
      steps {
        sh 'trivy image --exit-code 1 --severity HIGH,CRITICAL "$IMAGE_REPO:$BUILD_NUMBER"'
      }
    }
    stage('Push to ECR') {
      steps {
        sh 'docker push "$IMAGE_REPO:$BUILD_NUMBER"'
        sh 'docker push "$IMAGE_REPO:latest"'
      }
    }
    stage('Update Deployment') {
      steps {
        sh 'sed -i "s|image:.*|image: $IMAGE_REPO:$BUILD_NUMBER|g" deploy-svc.yaml'
      }
    }
    stage('Deploy to Kubernetes') {
      steps {
        sh '''#!/bin/bash -l
aws eks update-kubeconfig \
  --region us-east-2 \
  --name zhoujie-devsecops-eks \
  --kubeconfig /home/jenkins/.kube/config

kubectl create ns zhoujie-devsecops
kubectl apply -f deploy-svc.yaml

kubectl rollout status -n zhoujie-devsecops deployment/zhoujie-devsecops-demo --timeout=60s || {
  kubectl rollout undo -n zhoujie-devsecops deployment/zhoujie-devsecops-demo || true
  exit 1
}
'''
      }
    }
  }
  post {
    success { 
      echo "Build ${env.BUILD_NUMBER} succeeded" 
    }
    failure { 
      echo "Build ${env.BUILD_NUMBER} failed" 
    }
    always { 
      echo "Build ${env.BUILD_NUMBER} finished" 
    }
  }
}
