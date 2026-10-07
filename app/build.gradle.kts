plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.ipa_board"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.ipa_board"
        minSdk = 35
        targetSdk = 36
        versionCode = 2
        versionName = "0.0.2-dev"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }

        testInstrumentationRunner = "com.example.ipa_board.RegressionTestRunner"
    }

    packaging { jniLibs { useLegacyPackaging = true } }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// Device runs must snapshot and restore the original installation and configuration.
// The protected host runner builds these APKs, then installs/instruments them itself.
tasks.configureEach {
    val deviceTask = javaClass.name.contains("AndroidTestTask") ||
        javaClass.name.contains("ManagedDeviceInstrumentationTestTask") ||
        javaClass.name.contains("ManagedDeviceInstrumentationTestSetupTask")
    if (deviceTask || ((name.startsWith("connected") || name.startsWith("managedDevice")) &&
        name.endsWith("AndroidTest"))) {
        doFirst {
            throw GradleException("Device regression requires backup and rollback. Use python3 tools/regression.py run --suite full --serial <device> instead.")
        }
    }
}

dependencies {
    implementation(files("libs/jna-5.17.0.aar"))
    implementation(files("libs/onnxruntime-android-1.23.2.aar", "libs/onnxruntime-extensions-android-0.13.0.aar"))
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.play.services.auth.api.phone)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
