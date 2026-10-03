plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    explicitApi()
}

dependencies {
    // StateFlow appears in PlaybackEngine's public signature, so this is api, not implementation.
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}
