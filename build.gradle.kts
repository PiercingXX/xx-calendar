// Pins: AGP 8.9.1 + Gradle 8.11.1 + compileSdk 35 follow the environment-proven
// xx-phone baseline (platforms 34 is absent on this machine). Kotlin 2.1.20 with
// the matching Compose compiler plugin follows the xx-note baseline.
plugins {
    id("com.android.application") version "8.9.1" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
