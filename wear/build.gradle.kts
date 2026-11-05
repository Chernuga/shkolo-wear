plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "bg.shkolo.wear"
    compileSdk = 35

    defaultConfig {
        applicationId = "bg.shkolo.wear"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    // Release signing. The keystore is not committed; see keystore/README.md.
    val ks = rootProject.file("keystore/shkolo-release.jks")
    signingConfigs {
        if (ks.exists()) {
            create("release") {
                storeFile = ks
                storePassword = (project.findProperty("shkoloStorePass") ?: "shkolo123") as String
                keyAlias = "shkolo"
                keyPassword = (project.findProperty("shkoloKeyPass") ?: "shkolo123") as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (ks.exists()) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":common"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("androidx.compose.ui:ui:1.7.5")
    implementation("androidx.compose.foundation:foundation:1.7.5")
    implementation("androidx.wear.compose:compose-material:1.4.1")
    implementation("androidx.wear.compose:compose-foundation:1.4.1")

    implementation("com.google.android.gms:play-services-wearable:18.2.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
