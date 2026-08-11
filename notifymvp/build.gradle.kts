plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

group   = "com.github.aslamSk301"
version = "1.1.1"

android {
    namespace   = "com.notifymvp.sdk"
    compileSdk  = 35

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Firebase Messaging (compileOnly — host app provides it)
    compileOnly(platform("com.google.firebase:firebase-bom:33.1.0"))
    compileOnly("com.google.firebase:firebase-messaging-ktx")

    // Encrypted storage for deviceId
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Core AndroidX
    implementation("androidx.core:core-ktx:1.13.1")
}
