plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dyslexia2813.teliktv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dyslexia2813.teliktv"
        minSdk = 24
        targetSdk = 28
        versionCode = 1
        versionName = "0.1-test"
    }
}

dependencies {
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
}
