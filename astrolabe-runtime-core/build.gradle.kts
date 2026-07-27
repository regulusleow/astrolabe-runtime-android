plugins {
    id("astrolabe.android.library")
}

android {
    namespace = "dev.astrolabe.runtime.core"
}

dependencies {
    api(libs.astrolabe.protocol.kotlin)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
