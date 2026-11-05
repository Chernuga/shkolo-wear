plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "bg.shkolo.phone"
    compileSdk = 35

    defaultConfig {
        applicationId = "bg.shkolo.phone"
        minSdk = 26
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
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Wearable Data Layer: how the watch reaches the phone.
    implementation("com.google.android.gms:play-services-wearable:18.2.0")
}
