plugins {
    id("astrolabe.android.library")
}

android {
    namespace = "dev.astrolabe.runtime"

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        buildConfigField(
            "String",
            "ASTROLABE_RUNTIME_VERSION",
            "\"${providers.gradleProperty("astrolabeRuntimeVersion").get()}\""
        )
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    implementation(project(":astrolabe-runtime-core"))
    implementation(project(":astrolabe-runtime-view"))
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
