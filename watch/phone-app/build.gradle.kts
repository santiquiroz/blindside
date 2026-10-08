import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.santiquiroz.blindside.phone"
    compileSdk = 36

    defaultConfig {
        // Spec §4: the Wear Data Layer only links apps that share the applicationId and the signing key.
        applicationId = "io.github.santiquiroz.blindside"
        // Deviation P1: android-shared needs API 33 (plan 05 D1); 31 fails the manifest merger.
        minSdk = 33
        targetSdk = 36
        versionCode = 3
        versionName = "1.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":android-shared"))
    implementation(project(":radar-core"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.play.services)
    implementation(libs.datastore.preferences)
    implementation(libs.play.services.wearable)

    testImplementation(platform(libs.junit5.bom))
    testImplementation(libs.junit5.jupiter)
    testRuntimeOnly(libs.junit5.launcher)
}
