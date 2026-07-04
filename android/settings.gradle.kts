pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "sms-spam-shield-android"
include(":spamshield")      // core AAR: SpamShield facade + engine + refresh worker
include(":spamshield-ai")   // optional L1 AI layer (Nano / cloud)
