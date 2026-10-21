plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
    kotlin("plugin.serialization")
}

// Firebase config for push notifications (heating-notifier project); kept out of git. Without it the
// app builds and runs, it just never gets a push token.
if (file("google-services.json").exists()) apply(plugin = "com.google.gms.google-services")

android {
    namespace = "heating.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "heating.app"
        minSdk = 33 // Android 13 and newer
        targetSdk = 35
        versionCode = 25
        versionName = "0.13.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

val ktorVersion = "3.1.3"

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Charts. 2.2.0 matches this Kotlin (2.1.21) and AGP 8.7; newer Vico needs Kotlin 2.2+/AGP 8.9+/SDK 36.
    implementation("com.patrykandpatrick.vico:compose-m3:2.2.0")

    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging")

    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-okhttp:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-client-websockets:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("io.ktor:ktor-client-mock:$ktorVersion")
}
