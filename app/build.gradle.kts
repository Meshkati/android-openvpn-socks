plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing comes from the environment (CI secrets). Without it,
// assembleRelease builds an unsigned APK, as before.
val releaseKeystore = providers.environmentVariable("RELEASE_KEYSTORE").orNull

android {
    namespace = "io.github.meshkati.ovpnvless"
    compileSdk = 36
    // Pinned in gradle.properties; override with ./gradlew -PndkVersion=...
    providers.gradleProperty("ndkVersion").orNull?.let { ndkVersion = it }

    defaultConfig {
        applicationId = "io.github.meshkati.ovpnvless"
        minSdk = 26
        targetSdk = 36
        // CI sets these from the release tag: -PversionCode=... -PversionName=...
        versionCode = providers.gradleProperty("versionCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("versionName").orNull ?: "0.1"

        ndk {
            // Keep in sync with the -target list in scripts/build-engine.sh.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
    }

    externalNativeBuild {
        cmake {
            // Downloads OpenVPN 3 core, asio, mbed TLS, lz4 and fmt at configure time.
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = providers.environmentVariable("RELEASE_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("RELEASE_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("RELEASE_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        // A separate app id lets a debug build (./gradlew installDebug) sit next to
        // an installed release instead of clashing with its signing key.
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            if (releaseKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        // BuildConfig.VERSION_NAME: the update check compares it with the latest release.
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Built by scripts/build-engine.sh (gomobile bind of /engine: Xray-core + gVisor).
    implementation(files("libs/engine.aar"))

    testImplementation("junit:junit:4.13.2")
}
