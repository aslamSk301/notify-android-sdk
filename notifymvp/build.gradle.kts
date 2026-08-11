plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace   = "com.notifymvp.sdk"
    compileSdk  = 35

    defaultConfig {
        minSdk  = 21
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
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    // Networking
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // Firebase Messaging (compileOnly — host app provides it)
    compileOnly(platform(libs.firebase.bom))
    compileOnly(libs.firebase.messaging.ktx)

    // Encrypted storage for deviceId
    implementation(libs.androidx.security.crypto)

    // Core AndroidX
    implementation(libs.androidx.core.ktx)
}
