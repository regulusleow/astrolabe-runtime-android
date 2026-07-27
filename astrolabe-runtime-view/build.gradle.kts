plugins {
    id("astrolabe.android.library")
}

android {
    namespace = "dev.astrolabe.runtime.view"
}

dependencies {
    implementation(project(":astrolabe-runtime-core"))
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
