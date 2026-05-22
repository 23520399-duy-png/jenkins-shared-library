def call(Map config = [:]) {
    pipeline {
        agent any
        environment {
            SERVICE_NAME = "${config.serviceName}"
            ACR_URL      = "acrdevopsprod2025.azurecr.io"
            IMAGE_TAG    = "${BUILD_NUMBER}-${GIT_COMMIT.take(7)}"
            FULL_IMAGE   = "${ACR_URL}/${SERVICE_NAME}:${IMAGE_TAG}"
            SONAR_TOKEN  = credentials('sonar-token')
            SCANNER_HOME = tool 'SonarQube Scanner'
        }
        options {
            timeout(time: 30, unit: 'MINUTES')
            buildDiscarder(logRotator(numToKeepStr: '10'))
            disableConcurrentBuilds()
        }

        stages {
            stage('1. Checkout') {
                steps { checkout scm }
            }

            stage('2. IaC Security - Checkov') {
                steps {
                    withCredentials([string(credentialsId:'github-token', variable:'GH_TOKEN')]) {
                        sh '''
                            rm -rf /tmp/manifest-repo
                            git clone https://${GH_TOKEN}@github.com/23520399-duy-png/manifest-repo.git /tmp/manifest-repo
                            echo "Chạy Checkov quét cấu hình K8s..."
                            checkov -d /tmp/manifest-repo/helm/online-boutique \
                                --framework helm \
                                --skip-check CKV_K8S_8,CKV_K8S_9,CKV_K8S_10,CKV_K8S_11,CKV_K8S_12,CKV_K8S_13,CKV_K8S_15,CKV_K8S_17,CKV_K8S_18,CKV_K8S_19,CKV_K8S_21,CKV_K8S_22,CKV_K8S_28,CKV_K8S_29,CKV_K8S_30,CKV_K8S_31,CKV_K8S_37,CKV_K8S_38,CKV_K8S_40,CKV_K8S_43,CKV2_K8S_6 \
                                --output cli \
                                --compact
                            rm -rf /tmp/manifest-repo
                        '''
                    }
                }
            }

            stage('3. Unit Test (Polyglot)') {
                steps {
                    dir("src/${SERVICE_NAME}") {
                        // Đã gỡ bỏ các đoạn || echo để ép pipeline FAIL nếu test xịt
                        sh '''
                            if [ -f "go.mod" ]; then
                                echo "Go service detected"
                                go test ./... -v -cover
                            elif [ -f "requirements.txt" ]; then
                                echo "Python service detected"
                                pip install --break-system-packages -r requirements.txt && pytest --cov
                            elif [ -f "package.json" ]; then
                                echo "Node.js service detected"
                                npm ci && npm test
                            elif [ -f "pom.xml" ] || [ -f "build.gradle" ]; then
                                echo "Java service detected"
                                ./mvnw test || ./gradlew test
                            else
                                echo "No test framework detected for ${SERVICE_NAME}"
                            fi
                        '''
                    }
                }
            }

            stage('4. SCA - OWASP Dependency Check') {
                steps {
                    dependencyCheck additionalArguments: "--scan src/${SERVICE_NAME} --format XML --out . --noupdate", odcInstallation: 'OWASP-DC'
                }
            }

            stage('5. SAST - SonarQube') {
                steps {
                    withSonarQubeEnv('SonarQube') {
                        sh """
                            \${SCANNER_HOME}/bin/sonar-scanner \
                              -Dsonar.projectKey=\${SERVICE_NAME} \
                              -Dsonar.sources=src/\${SERVICE_NAME} \
                              -Dsonar.token=\${SONAR_TOKEN}
                        """
                    }
                }
            }

            stage('6. Build Docker Image') {
                steps {
                    sh "docker build -t \${FULL_IMAGE} -f src/\${SERVICE_NAME}/Dockerfile ./src/\${SERVICE_NAME}"
                }
            }

            stage('7. Scan Image - Trivy') {
                steps {
                    // Đã đổi exit-code thành 1 để chặn các Image lỗi Critical
                    sh "trivy image --exit-code 1 --severity CRITICAL \${FULL_IMAGE}"
                }
            }

            stage('8. Push to ACR') {
                steps {
                    withCredentials([usernamePassword(credentialsId: 'acr-credentials', passwordVariable: 'ACR_PASS', usernameVariable: 'ACR_USER')]) {
                        sh "docker login \${ACR_URL} -u \${ACR_USER} -p \${ACR_PASS}"
                        sh "docker push \${FULL_IMAGE}"
                    }
                }
            }

            stage('9. GitOps - Update Local Helm Values') {
                steps {
                    withCredentials([usernamePassword(credentialsId: 'github-token-git', passwordVariable: 'GH_TOKEN', usernameVariable: 'GH_USER')]) {
                        sh '''
                            rm -rf manifest-tmp
                            git clone https://${GH_TOKEN}@github.com/23520399-duy-png/manifest-repo.git manifest-tmp
                            cd manifest-tmp

                            wget -qO ./yq https://github.com/mikefarah/yq/releases/latest/download/yq_linux_amd64
                            chmod +x ./yq

                            ./yq -i '.images.repository = "'${ACR_URL}'"' helm/online-boutique/values.yaml
                            ./yq -i '.'${SERVICE_NAME}'.image.tag = "'${IMAGE_TAG}'"' helm/online-boutique/values.yaml

                            git config user.name "Jenkins CI Bot"
                            git config user.email "jenkins@ci.local"
                            git add helm/online-boutique/values.yaml
                            git commit -m "ci: update ${SERVICE_NAME} to ${IMAGE_TAG} [skip ci]" || echo "No changes"
                            git push origin main
                        '''
                    }
                }
            }
        }
        post {
            always { cleanWs() }
        }
    }
}
