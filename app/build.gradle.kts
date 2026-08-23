import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing is optional: when keystore.properties exists at the repo root
// (storeFile / storePassword / keyAlias / keyPassword) the release buildType
// signs with it; without the file release falls back to the debug key so CI and
// fresh clones keep building. keystore.properties itself is gitignored.
val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.piercingxx.calendar"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.piercingxx.calendar"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Shared PiercingXX sideload identity (copied from nope-mode). Changing
        // this later means uninstall-and-lose-state on the phone — never rotate.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            // R8 stays off until the instrumented suite has run against a real
            // device (review P1): minification without that evidence risks
            // shipping a stripped reflection/provider surface we cannot verify.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (keystoreProperties.isNotEmpty()) {
                signingConfigs.getByName("release")
            } else {
                // Loud, not silent: without keystore.properties a "release" APK
                // carries the public, committed debug identity — fine for
                // personal sideloads, never for distribution.
                logger.warn(
                    "XX-Calendar: no keystore.properties found — " +
                        "release APK will be signed with the shared DEBUG key. " +
                        "Provide keystore.properties for a distributable release.",
                )
                signingConfigs.getByName("debug")
            }
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

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    lint {
        // OldTargetApi: targetSdk 35 is deliberate until the instrumented
        // suite has run against a real device (review P2) — a targetSdk bump
        // changes runtime behavior and needs device verification, so the
        // "not on the very latest" warning is suppressed rather than obeyed.
        disable += "OldTargetApi"
    }
}

// Every dependency for every workstream lives here so that no feature work ever
// edits this file. Robolectric is 4.13 (not the spec's 4.12.2) because the
// hybrid decision raised compileSdk to 35; unit tests pin sdk=34 via
// src/test/resources/robolectric.properties.
dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    // 2.7.0, not 2.8.x: LocalLifecycleOwner moved in 2.8 and breaks on BOM 2024.06.
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    implementation("androidx.glance:glance-appwidget:1.1.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.10")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")

    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.6.1")
    androidTestImplementation("androidx.test:rules:1.6.1")
}
