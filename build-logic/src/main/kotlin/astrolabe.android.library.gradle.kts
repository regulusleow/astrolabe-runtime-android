import com.android.build.api.dsl.LibraryExtension

plugins {
    id("com.android.library")
}

extensions.configure<LibraryExtension> {
    compileSdk = 35

    defaultConfig {
        minSdk = 23
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}
