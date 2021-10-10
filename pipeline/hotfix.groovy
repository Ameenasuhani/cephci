// Script to trigger when a RH Ceph is hotfix released and execute Tier-0 and Tier-1 using the bits available in the external repository.
// Global variables section
def nodeName = "centos-7"
def sharedLib
def versions
def cephVersion
def composeUrl
def containerImage
def buildPhase = "hotfix"
def tierLevel0 = "tier-0"
def tierLevel1 = "tier-1"
def testStages = [:]
def testResults = [:]


// Pipeline script entry point
node(nodeName) {

    timeout(unit: "MINUTES", time: 30) {
        stage('Preparing') {
            if (env.WORKSPACE) {
                sh script: "sudo rm -rf *"
            }
            checkout([
                $class: 'GitSCM',
                branches: [[ name: '*/pipeline_hotfix' ]],
                doGenerateSubmoduleConfigurations: false,
                extensions: [[
                    $class: 'CloneOption',
                    shallow: true,
                    noTags: false,
                    reference: '',
                    depth: 0
                ]],
                submoduleCfg: [],
                userRemoteConfigs: [[
                    url: 'https://github.com/Ameenasuhani/cephci.git'
                ]]
            ])
            sharedLib = load("${env.WORKSPACE}/pipeline/vars/lib.groovy")
            sharedLib.prepareNode()
        }
    }

    stage('Updating') {
        echo "${params.CI_MESSAGE}"

        versions = sharedLib.fetchMajorMinorOSVersion("hotfix")
        def majorVersion = versions.major_version
        def minorVersion = versions.minor_version
        def platform = versions.platform

        def cimsg = sharedLib.getCIMessageMap()
        def repoDetails = cimsg.build.extra.image

        containerImage = repoDetails.index.pull.find({ x -> !(x.contains("sha")) })

        def repoUrl = repoDetails.yum_repourls.find({ x -> x.contains("RHCEPH") })
        composeUrl = repoUrl.split("work").find({
            x -> x.contains("RHCEPH-${majorVersion}.${minorVersion}")
        })
        println "repo url : ${composeUrl}"

        cephVersion = sharedLib.fetchCephVersion(composeUrl)

        releaseContent = sharedLib.readFromReleaseFile(majorVer, minorVer)
        if ( releaseContent?.latest?."ceph-version") {
            currentCephVersion = releaseContent["latest"]["ceph-version"]
            def compare = sharedLib.compareCephVersion(currentCephVersion, cephVersion)

            if (compare == -1) {
                sharedLib.unSetLock(majorVer, minorVer)
                currentBuild.result = "ABORTED"
                println "Build Ceph Version: ${cephVersion}"
                println "Found Ceph Version: ${currentCephVersion}"
                error("The latest ceph version is lower than existing one.")
            }
        }

        if ( !releaseContent.containsKey("latest") ) {
            releaseContent.latest = [:]
            releaseContent.latest.composes = [:]
        }

        releaseContent["latest"]["ceph-version"] = cephVersion
        releaseContent["latest"]["composes"]["${platform}"] = composeUrl
        sharedLib.writeToReleaseFile(majorVer, minorVer, releaseContent)

        def bucket = "ceph-${majorVer}.${minorVer}-${platform}"
        sharedLib.uploadCompose(bucket, cephVersion, composeUrl)
    }

    stage(" tier-0 suites") {
        versions = sharedLib.fetchMajorMinorOSVersion("hotfix")
        def majorVersion = versions.major_version
        def minorVersion = versions.minor_version

        def cimsg = sharedLib.getCIMessageMap()
        def repoDetails = cimsg.build.extra.image

        containerImage = repoDetails.index.pull.find({ x -> !(x.contains("sha")) })

        def repoUrl = repoDetails.yum_repourls.find({ x -> x.contains("RHCEPH") })
        composeUrl = repoUrl.split("work").find({
            x -> x.contains("RHCEPH-${majorVersion}.${minorVersion}")
        })
        println "repo url : ${composeUrl}"

        cephVersion = sharedLib.fetchCephVersion(composeUrl)
        testStages = sharedLib.fetchStages(buildPhase, tierLevel0, testResults)
    }

    parallel testStages

    if (!("FAIL" in testResults.values())) {
        stage(" tier-1 suites") {
            versions = sharedLib.fetchMajorMinorOSVersion("hotfix")
            def majorVersion = versions.major_version
            def minorVersion = versions.minor_version

            def cimsg = sharedLib.getCIMessageMap()
            def repoDetails = cimsg.build.extra.image

            containerImage = repoDetails.index.pull.find({ x -> !(x.contains("sha")) })

            def repoUrl = repoDetails.yum_repourls.find({ x -> x.contains("RHCEPH") })
            composeUrl = repoUrl.split("work").find({
                x -> x.contains("RHCEPH-${majorVersion}.${minorVersion}")
            })
            println "repo url : ${composeUrl}"

            cephVersion = sharedLib.fetchCephVersion(composeUrl)
            testStages = sharedLib.fetchStages(buildPhase, tierLevel1, testResults)
        }

    }

    parallel testStages

    stage('Publish Results') {
        def status = 'PASSED'
        if ("FAIL" in testResults.values()) {
           status = 'FAILED'
        }

        def contentMap = [
            "artifact": [
                "name": "Red Hat Ceph Storage",
                "nvr": "RHCEPH-${versions.major_version}.${versions.minor_version}",
                "phase": "hotfix",
                "type": "released-hotfix",
                "version": cephVersion
            ],
            "build": [
                "repository": "cdn.redhat.com"
            ],
            "contact": [
                "email": "ceph-qe@redhat.com",
                "name": "Downstream Ceph QE"
            ],
            "run": [
                "log": "${env.BUILD_URL}console",
                "url": env.BUILD_URL
            ],
            "test": [
                "category": "release",
                "result": status
            ],
            "version": "1.1.0"
        ]

        def msgContent = writeJSON returnText: true, json: contentMap
        def overrideTopic = "VirtualTopic.qe.ci.rhceph.test.complete"

        sharedLib.SendUMBMessage(msgContent, overrideTopic, "TestingCompleted")
        println "Updated UMB Message Successfully"

        def msg = [
            "product": "Red Hat Ceph Storage",
            "version": contentMap["artifact"]["nvr"],
            "ceph_version": contentMap["artifact"]["version"],
            "container_image": contentMap["build"]["repository"]
        ]

        sharedLib.sendEmail(testResults, msg, tierLevel.capitalize())
    }
}
