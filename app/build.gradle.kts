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

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
