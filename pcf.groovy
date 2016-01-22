/* Ensure the Jenkins environment has the following defined
1) PCF credentials to enable a push defined with credential ID 'pcf-hackney-deploy'
2) A slave - local / shared with the label 'shared-built-in'
3) Timestamp plugin installed
4) MVN tool installed with the label 'Maven 3 (built-in)'
5) 
*/


banner "Started"

stage 'Commit'
banner "Commit"

node('shared-built-in') {

  wrap([$class: 'TimestamperBuildWrapper']) {
    sh 'date'
    // COMPILE AND JUNIT
    echo "INFO - Starting build phase"
    def src = 'https://github.com/harniman/spring-petclinic.git'
    //def src = '/Users/nharniman/git/spring-petclinic'
    git url: src

    ensureMaven()


    sh 'mvn clean package site'
    sh 'tar -c -f src.tar src/ pom.xml'
    dir('target') {
        stash includes: '*.war', name: 'PCF-Petclinic-war'
    }    
        
    stash includes: 'src.tar', name: 'PCF-Petclinic-src'

    step([$class: 'ArtifactArchiver', allowEmptyArchive: false, artifacts: 'target/site/**', excludes: null])
    step $class: 'hudson.tasks.junit.JUnitResultArchiver', testResults: 'target/surefire-reports/*.xml'
    echo "INFO - Ending build phase"
  }
}
checkpoint 'Build complete'

banner "PCF Dev Deploy and Sonar QA"

stage name: 'PCF Dev Deploy and Sonar QA', concurrency: 1

parallel qualityAnalysis: {

    node('shared-built-in') {
       wrap([$class: 'TimestamperBuildWrapper']) {

          // RUN SONAR ANALYSIS
          echo "INFO - Starting SONAR"
          unstash 'PCF-Petclinic-src'
          sh 'ls -l'
          ensureMaven()
          sh 'tar -x -f src.tar'
          //sh 'mvn sonar:sonar'

          echo "INFO - Ending SONAR"
      }
    }
}, devDeploy: {

    node('shared-built-in') {
      wrap([$class: 'TimestamperBuildWrapper']) {
        deploy('dev-nigel-petclinic', 'development', 'PCF-Petclinic-war')
      }
      echo "INFO - Ending Dev Deploy"
    }
  
}, 
failFast: true

checkpoint 'QA complete'


banner "PCF Perf Deploy"

stage name: 'PCF Perf Deploy', concurrency: 1

node ("shared-built-in") {
  wrap([$class: 'TimestamperBuildWrapper']) {
    deploy('perf-nigel-petclinic', 'performance', 'PCF-Petclinic-war')
 }
}

checkpoint 'Perf Deploy Complete'

banner "Perf Tests"

stage name: 'Run Perf Tests', concurrency: 1

node ("shared-built-in") {
  wrap([$class: 'TimestamperBuildWrapper']) {

         unstash 'PCF-Petclinic-src'
         sh 'tar -x -f src.tar'
         ensureMaven()
         sh 'mvn -DskipTests=true -Djmeter.hostname=perf-nigel-petclinic.hackney.cf-app.com -Djmeter.port=443 -Djmeter.proto=https verify'  

  }
}
checkpoint 'Perf Tests Complete'

banner "PCF Production Deploy"

stage name: 'PCF Production Deploy', concurrency: 1

input message: "Are you ready to deploy to production?", ok: "DEPLOY TO PRODUCTION"

node ("shared-built-in") {
  wrap([$class: 'TimestamperBuildWrapper']) {
    deploy('nigel-petclinic', 'production', 'PCF-Petclinic-war')
 }
}

banner "Finished"

// FUNCTIONS

def deploy(route, env, stashsrc) {
        unstash stashsrc

        wrap([$class: 'CloudFoundryCliBuildWrapper', 
          apiEndpoint: 'https://api.hackney.cf-app.com', 
          skipSslValidation: true, 
          cloudFoundryCliVersion: 'CF-CLI-6.12.2', 
          credentialsId: 'pcf-hackney-deploy',  
          organization: 'cloudbees', 
          space: env]) { 
             sh "cf push $route -p petclinic.war"
          }
}
/**
 * Deploy Maven on the slave if needed and add it to the path
 */
def ensureMaven() {
    env.PATH = "${tool 'Maven 3 (built-in)'}/bin:${env.PATH}"
}

def banner(name) {

  echo """****************************************************
*  $name
****************************************************"""

}
