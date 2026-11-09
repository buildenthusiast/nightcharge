import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
}

group = "bg.solar"
version = "1.0.0"

repositories {
    google()
    mavenCentral()
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    // org.json е от Android SDK; за JVM използваме самостоятелната библиотека.
    implementation("org.json:json:20240303")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")
}


compose.desktop {
    application {
        mainClass = "bg.solar.nightcharge.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "NightCharge"
            packageVersion = "1.0.0"
            windows {
                menuGroup = "NightCharge"
                upgradeUuid = "6f1c2a9e-3b7d-4e1f-9a2b-nightcharge01"
            }
        }
    }
}
