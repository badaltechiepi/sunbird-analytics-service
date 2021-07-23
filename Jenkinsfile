node('build-slave') {
    try {
        ansiColor('xterm') {
            stage('Checkout') {
                if (!env.hub_org) {
                    println( "Uh Oh! Please set a Jenkins environment variable named hub_org with value as registery/sunbidrded" )
                    error 'Please resolve the errors and rerun..'
                } else
                    println( "Found environment variable named hub_org with value as: " )
            }

            cleanWs()            
            checkout scm
            commit_hash = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
            build_tag = sh(script: "echo " + params.github_release_tag.split('/')[-1] + "_" + commit_hash + "_" + env.BUILD_NUMBER, returnStdout: true).trim()
            echo "build_tag: " + build_tag
        }
    }
    catch (err) {
        currentBuild.result = "FAILURE"
        throw err
    }
}
