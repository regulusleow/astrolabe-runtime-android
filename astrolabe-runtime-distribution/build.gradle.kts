plugins {
    id("astrolabe.android.fused-distribution")
}

val runtimeVersion = providers.gradleProperty("astrolabeRuntimeVersion")

androidFusedLibrary {
    namespace = "dev.astrolabe.runtime.distribution"
    minSdk {
        version = release(23)
    }
}

dependencies {
    include(project(":astrolabe-runtime-core"))
    include(project(":astrolabe-runtime-view"))
    include(project(":astrolabe-runtime"))
}

mavenPublishing {
    publishToMavenCentral()
    coordinates(
        groupId = "io.github.regulusleow",
        artifactId = "astrolabe-runtime-android",
        version = runtimeVersion.get()
    )

    pom {
        name.set("Astrolabe Runtime Android")
        description.set("Android View runtime inspection support for Astrolabe.")
        inceptionYear.set("2026")
        url.set("https://github.com/regulusleow/astrolabe-runtime-android")

        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }

        developers {
            developer {
                id.set("regulusleow")
                name.set("Regulus Leow")
                url.set("https://github.com/regulusleow")
            }
        }

        scm {
            url.set("https://github.com/regulusleow/astrolabe-runtime-android")
            connection.set(
                "scm:git:https://github.com/regulusleow/astrolabe-runtime-android.git"
            )
            developerConnection.set(
                "scm:git:ssh://git@github.com/regulusleow/astrolabe-runtime-android.git"
            )
        }
    }
}

publishing {
    repositories {
        maven {
            name = "CentralStaging"
            url = rootProject.layout.buildDirectory
                .dir("central-staging")
                .get()
                .asFile
                .toURI()
        }
    }
}
