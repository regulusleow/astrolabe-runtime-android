plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.android.gradle.plugin)
    implementation(libs.maven.publish.plugin)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}
