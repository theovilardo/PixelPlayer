plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.theveloper.pixelplay.usbaudio"
    compileSdk = 37
    // r28 LTS: 16 KB page-size alignment is the default, required for targetSdk 37 devices.
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 30 // Must match app module's minSdk

        ndk {
            // Keep in sync with the ABI splits declared in app/build.gradle.kts.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.params)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junitplatformlauncher)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.truth)
}
