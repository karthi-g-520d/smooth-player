plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.example.smoothplayer.engine.impl"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(project(":engine:api"))
    testImplementation(libs.junit)
}
