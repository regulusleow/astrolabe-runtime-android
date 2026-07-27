plugins {
    id("com.android.fused-library")
    id("com.vanniktech.maven.publish")
}

tasks.register<VerifyDistributionArtifactTask>("verifyDistributionArtifact") {
    group = "verification"
    description = "Verifies the public fused AAR and Maven publication metadata."
    dependsOn(
        "bundle",
        "generateMetadataFileForMavenPublication",
        "generatePomFileForMavenPublication",
        "mergingArtifactSOURCES_JAR"
    )
    aarFile.set(
        layout.buildDirectory.file(
            "outputs/aar/astrolabe-runtime-distribution.aar"
        )
    )
    pomFile.set(
        layout.buildDirectory.file(
            "publications/maven/pom-default.xml"
        )
    )
    sourcesJarFile.set(
        layout.buildDirectory.file(
            "intermediates/merged_sources_jar/single/" +
                "mergingArtifactSOURCES_JAR/sources.jar"
        )
    )
    moduleMetadataFile.set(
        layout.buildDirectory.file(
            "publications/maven/module.json"
        )
    )
}
