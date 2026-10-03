plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.example.smoothplayer.feature.player"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 26
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":engine:api"))
    // Brings androidx.fragment transitively; the screen is a Fragment.
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)
    // ActivityResultContracts for the player's file picker.
    implementation(libs.androidx.activity)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
