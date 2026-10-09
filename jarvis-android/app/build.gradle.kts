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
        // Every CI build gets its own number, so Settings and the release page
        // say which one is installed; a local build stays plain 6.0. The code
        // keeps counting from 5.5's, so 6.0 installs over it in place.
        val ciRun = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0
        versionCode = 23 + ciRun
        versionName = if (ciRun > 0) "6.0.$ciRun" else "6.0"
        vectorDrawables { useSupportLibrary = true }

        // Phones only. The on-device vision models ship native code for every
        // processor family; the two x86 ones are emulators, and carrying them
        // doubled the download for no one.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
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

    testOptions {
        unitTests {
            // Robolectric needs the merged manifest and resources to start the
            // real activity for the screenshots.
            isIncludeAndroidResources = true
            all {
                it.systemProperty(
                    "screens.dir",
                    project.layout.buildDirectory.dir("screens").get().asFile.absolutePath
                )
                it.maxHeapSize = "3g"
                it.testLogging.showStandardStreams = true
            }
        }
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
    // Runs a quiet routine at its time with the app closed, and retries it
    // when the network was down.
    implementation(libs.androidx.work.runtime.ktx)

    // On-device eyes that need no key: reading text, naming things, QR codes.
    // The models are bundled, so they work offline from the first launch.
    implementation(libs.mlkit.text)
    implementation(libs.mlkit.labels)
    implementation(libs.mlkit.barcode)

    debugImplementation(libs.androidx.compose.ui.tooling)

    // Plain JVM tests for the logic that needs no phone. The real org.json
    // replaces android.jar's stubs, which only throw.
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.okhttp.mockwebserver)
    // A real SQLite for the upgrade test: a 5.5 database is built, upgraded
    // by the phone's own statements and checked row by row.
    testImplementation(libs.sqlite.jdbc)

    // Screenshots of the real app, rendered on the JVM. Debug-only on purpose:
    // the release build and its tests never compile or download any of it, so
    // the screenshot job cannot break the APK.
    testDebugImplementation(libs.robolectric)
    // The canvas walked as a person would see it: every moment composed, its
    // primary step found on screen and tapped. Debug-only, like the screenshots.
    testDebugImplementation(platform(libs.androidx.compose.bom))
    testDebugImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
