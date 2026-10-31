plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.meshkati.ovpnvless"
    compileSdk = 36
    // Use a specific installed NDK: ./gradlew -PndkVersion=27.2.12479018 ...
    providers.gradleProperty("ndkVersion").orNull?.let { ndkVersion = it }

    defaultConfig {
        applicationId = "io.github.meshkati.ovpnvless"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"

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

    buildTypes {
        release {
            isMinifyEnabled = false
        }
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
