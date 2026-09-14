import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.library")
    // SKIE : génère des bindings Swift idiomatiques (Flow → AsyncSequence, sealed → enum,
    // suspend → async throws). Actif uniquement à la compilation Kotlin/Native (iOS), sur macOS.
    id("co.touchlab.skie") version "0.10.7-preview.2.2.20"
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm()

    // Cibles iOS agrégées en XCFramework "Shared.xcframework" (device + simulateur).
    // Tâche `assembleSharedXCFramework` — à lancer sur macOS ; la config est inerte sur Windows.
    val xcf = XCFramework("Shared")
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
            xcf.add(this)
        }
        // cinterop vers WebRTC.framework (IosPeerLink). La tâche ne tourne qu'à la compilation
        // iOS (macOS) ; sur Windows elle est simplement enregistrée sans s'exécuter.
        target.compilations.getByName("main").cinterops.create("webrtc") {
            defFile(project.file("src/nativeInterop/cinterop/webrtc.def"))
            // -F : chemin du slice WebRTC.framework correspondant à la cible (fourni par la CI).
            // Propriété par cible (webrtc.framework.dir.iosSimulatorArm64…) ou globale en repli.
            val dir = (project.findProperty("webrtc.framework.dir.${target.targetName}")
                ?: project.findProperty("webrtc.framework.dir")) as String?
            if (dir != null) compilerOpts("-F", dir)
        }
    }

    sourceSets {
        commonMain.dependencies {
            // api : Instant/StateFlow apparaissent dans l'API publique du module.
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            api("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
        }
        androidMain.dependencies {
            // WebRTC natif (repackage Google libwebrtc, org.webrtc.*) + WebSocket de signalisation.
            implementation("io.getstream:stream-webrtc-android:1.3.10")
            implementation("com.squareup.okhttp3:okhttp:4.12.0")
        }
    }
}

// SKIE (defaults) : Flow → AsyncSequence, suspend → async throws, sealed → enum Swift,
// enums/data class → types Swift natifs. Aucune config requise pour ce dont on a besoin.

android {
    namespace = "com.bubble.shared"
    compileSdk = 36
    defaultConfig {
        // 26 = VibrationEffect.createWaveform avec amplitudes (socle du HapticEngine).
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
