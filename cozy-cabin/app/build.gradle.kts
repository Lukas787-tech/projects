plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.cozyhollow.riverside"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.cozyhollow.riverside"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("sideload") {
            storeFile = file("../keystore/riverside.jks")
            storePassword = "riverside"
            keyAlias = "riverside"
            keyPassword = "riverside"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("sideload")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf("DebugProbesKt.bin")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}
