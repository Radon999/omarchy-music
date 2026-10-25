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
        // NewPipe's extractor (YouTube's audio, for songs Tidal doesn't have) is published there only.
        maven("https://jitpack.io") { content { includeGroup("com.github.teamnewpipe"); includeGroup("com.github.TeamNewPipe") } }
    }
}
rootProject.name = "OmarchyMusic"
include(":app")
