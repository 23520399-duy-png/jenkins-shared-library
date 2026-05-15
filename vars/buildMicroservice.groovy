def call(Map config = [:]) {
    pipeline {
        agent any
        environment {
            SERVICE_NAME = config.serviceName
            ACR_URL      = "acrdevopsprod2025.azurecr.io"
            IMAGE_TAG    = "${BUILD_NUMBER}-${GIT_COMMIT.take(7)}"
            FULL_IMAGE   = "${ACR_URL}/${SERVICE_NAME}:${IMAGE_TAG}"
        }

        stages {
            stage('1. Checkout') {
                steps { checkout scm }
            }

            stage('2. Unit Test (Polyglot)') {
                steps {
                    dir("src/${SERVICE_NAME}") {
                        sh '''
                            if [ -f "go.mod" ]; then
                                echo "Go service detected"
                                go test ./... -v -cover || echo "No tests or Go not installed"
                            elif [ -f "requirements.txt" ]; then
                                echo "Python service detected"
                                pip install -r requirements.txt && pytest --cov || echo "No pytest"
                            elif [ -f "package.json" ]; then
                                echo "Node.js service detected"
                                npm ci && npm test || echo "No test script"
                            elif [ -f "pom.xml" ] || [ -f "build.gradle" ]; then
                                echo "Java service detected"
                                ./mvnw test || ./gradlew test || echo "Build tool not found"
                            else
                                echo "No test framework detected for ${SERVICE_NAME}"
                            fi
                        '''
                    }
                }
            }

            stage('3. Build Docker Image') {
                steps {
                    sh "docker build -t ${FULL_IMAGE} -f src/${SERVICE_NAME}/Dockerfile ."
                }
            }

            stage('4. Trivy Scan') {
                steps {
                    sh "trivy image --exit-code 0 --severity CRITICAL ${FULL_IMAGE}"
                }
            }

            stage('5. Push to ACR') {
                steps {
                    withCredentials([usernamePassword(credentialsId: 'acr-credentials', passwordVariable: 'ACR_PASS', usernameVariable: 'ACR_USER')]) {
                        sh "docker login ${ACR_URL} -u ${ACR_USER} -p ${ACR_PASS}"
                        sh "docker push ${FULL_IMAGE}"
                    }
                }
            }

            stage('6. GitOps - Update Local Helm Values') {
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
    }
}
