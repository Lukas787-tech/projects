plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.lukas.jarvis"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lukas.jarvis"
        minSdk = 26
        targetSdk = 35
        versionCode = 13
        versionName = "2.3"
        vectorDrawables { useSupportLibrary = true }
    }

    // A throwaway key committed to the repo on purpose: it keeps every CI build
    // signed identically so new APKs install straight over the old one.
    signingConfigs {
        create("personal") {
            storeFile = file("keystore/jarvis.jks")
            storePassword = "jarvis2024"
            keyAlias = "jarvis"
            keyPassword = "jarvis2024"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("personal")
            applicationIdSuffix = ".debug"
        }
        release {
            signingConfig = signingConfigs.getByName("personal")
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
