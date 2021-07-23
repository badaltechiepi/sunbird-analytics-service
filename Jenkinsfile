node('build-slave') {
    try {
        
            stage('Checkout') {
            cleanWs()            
            checkout scm
            commit_hash = sh(script: 'git rev-parse --short HEAD', returnStdout: true)
            build_tag = sh(script: "echo " + params.github_release_tag.split('/')[-1])
            echo "build_tag: " + build_tag
         }
    }
  
    catch (err) {
        currentBuild.result = "FAILURE"
        throw err
    }
}
