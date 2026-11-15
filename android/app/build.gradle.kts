plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
    kotlin("plugin.serialization")
}

// Firebase config for push notifications (heating-notifier project); kept out of git. Without it the
// app runs but never gets a push token, so building an APK needs it, or -PnoPush to mean that.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
} else if (!hasProperty("noPush")) {
    gradle.taskGraph.whenReady {
        if (allTasks.any { it.project == project && it.name.startsWith("assemble") }) {
            throw GradleException("android/app/google-services.json is missing, so this APK would get no push notifications. Add it, or pass -PnoPush.")
        }
    }
}

/** The release key's settings from the environment; a null value is missing. */
val releaseSigning: Map<String, String?> = listOf(
    "HEATING_RELEASE_KEYSTORE", "HEATING_RELEASE_STORE_PASSWORD", "HEATING_RELEASE_KEY_ALIAS", "HEATING_RELEASE_KEY_PASSWORD",
).associateWith { System.getenv(it)?.takeIf(String::isNotBlank) }

gradle.taskGraph.whenReady {
    if (allTasks.any { it.project == project && it.name.contains("Release") && (it.name.startsWith("assemble") || it.name.startsWith("package")) }) {
        val missing = releaseSigning.filterValues { it == null }.keys
        if (missing.isNotEmpty()) {
            throw GradleException("Release builds are signed with the stable release key, but ${missing.joinToString()} ${if (missing.size == 1) "is" else "are"} not set. See docs/android-release.md.")
        }
        val store = file(releaseSigning.getValue("HEATING_RELEASE_KEYSTORE")!!)
        if (!store.isFile) throw GradleException("HEATING_RELEASE_KEYSTORE points at $store, which does not exist.")
    }
}

android {
    namespace = "heating.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "heating.app"
        minSdk = 30 // Android 11 and newer (LineageOS 18.1 on the Echo Show 8)
        targetSdk = 35
        versionCode = 48
        versionName = "0.31.0"
    }

    // Release builds are signed with one stable key, so each new APK installs over the last (in-app updates rely on it).
    // The key comes from the environment; it is never in git. Without it a release build fails rather than falling back to
    // the debug key.
    signingConfigs {
        create("release") {
            releaseSigning.forEach { (name, value) -> if (value == null) return@create }
            storeFile = file(releaseSigning.getValue("HEATING_RELEASE_KEYSTORE")!!)
            storePassword = releaseSigning["HEATING_RELEASE_STORE_PASSWORD"]
            keyAlias = releaseSigning["HEATING_RELEASE_KEY_ALIAS"]
            keyPassword = releaseSigning["HEATING_RELEASE_KEY_PASSWORD"]
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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
        unitTests.isIncludeAndroidResources = true
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
    // The Live tab shows the bundled web view; the asset loader serves it over https so its module scripts load.
    implementation("androidx.webkit:webkit:1.12.1")

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
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("io.ktor:ktor-client-mock:$ktorVersion")
}
