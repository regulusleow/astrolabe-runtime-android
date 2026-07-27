pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

providers.gradleProperty("astrolabeProtocolPath").orNull
    ?.takeIf { it.isNotBlank() }
    ?.let { protocolPath ->
        val protocolDirectory = file(protocolPath)
        require(protocolDirectory.resolve("settings.gradle.kts").isFile) {
            "astrolabeProtocolPath must point to an Astrolabe Protocol Gradle build"
        }
        includeBuild(protocolDirectory) {
            dependencySubstitution {
                substitute(module("io.github.regulusleow:astrolabe-protocol-kotlin"))
                    .using(project(":AstrolabeProtocolKotlin"))
            }
        }
    }

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (providers.gradleProperty("astrolabeUseMavenLocal").orNull == "true") {
            mavenLocal()
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "astrolabe-runtime-android"
include(":astrolabe-runtime-core")
include(":astrolabe-runtime-view")
include(":astrolabe-runtime")
include(":astrolabe-runtime-distribution")
