plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.dagger.hilt.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.theveloper.pixelplay"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.theveloper.pixelplay"
        minSdk = 30
        targetSdk = 37
        versionCode = (project.findProperty("APP_VERSION_CODE") as? String)?.toInt() ?: 1
        versionName = (project.findProperty("APP_VERSION_NAME") as? String) ?: "1.0.0"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
            isShrinkResources = false
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            lint {
                abortOnError = false
                checkReleaseBuilds = false
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        freeCompilerArgs.add("-opt-in=com.google.android.horologist.annotations.ExperimentalHorologistApi")
    }
}

dependencies {
    implementation(project(":shared"))

    // Wear OS Compose
    implementation(libs.wear.compose.material)
    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.compose.navigation)

    // Horologist
    implementation(libs.horologist.compose.layout)
    implementation(libs.horologist.media.ui)
    implementation(libs.horologist.audio.ui)
    implementation(libs.horologist.composables)

    // Data Layer
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.play.services)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    compileOnly(libs.error.prone.annotations)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)

    // Compose core
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.foundation)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // Image loading
    implementation(libs.coil.compose)

    // Logging
    implementation(libs.timber)

    // Core
    implementation(libs.androidx.core.ktx)

    // Lifecycle
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Material icons for Wear
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.material.icons.extended)

    // Room (local database for transferred songs)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Media3 ExoPlayer (standalone local playback)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.mediarouter)

    constraints {
        // Fix vulnerabilities in transitive dependencies
        implementation(libs.netty.common)
        implementation(libs.netty.handler)
        implementation(libs.netty.codec.http)
        implementation(libs.netty.codec.http2)
        implementation(libs.bouncycastle.bcprov)
        implementation(libs.bouncycastle.bcpkix)
        implementation(libs.commons.lang3)
        implementation(libs.jdom2)
        implementation(libs.jose4j)
        implementation(libs.apache.httpclient)
    }
}
