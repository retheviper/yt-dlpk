plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvm("desktop")

    sourceSets {
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.commons.compress)
                implementation(libs.xz)
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(libs.kotest.runner.junit5)
                implementation(libs.kotest.assertions.core)
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "com.ytdlpk.app.MainKt"

        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Rpm
            )
            packageName = "yt-dlpk"
            packageVersion = "1.0.0"
            vendor = "yt-dlpk"
            description = "yt-dlp GUI desktop app built with Kotlin + Compose"
            modules("java.sql")
            macOS {
                iconFile.set(project.file("src/desktopMain/resources/icons/icon.icns"))
            }
            windows {
                iconFile.set(project.file("src/desktopMain/resources/icons/icon.ico"))
            }
            linux {
                iconFile.set(project.file("src/desktopMain/resources/icons/icon.png"))
            }
        }
    }
}
