// Pins: AGP 8.9.1 + Gradle 8.11.1 + compileSdk 35 follow the environment-proven
// xx-phone baseline (platforms 34 is absent on this machine). Kotlin and the
// Compose compiler keep the design.md §14 pins — no toolchain upgrade there.
plugins {
    id("com.android.application") version "8.9.1" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
