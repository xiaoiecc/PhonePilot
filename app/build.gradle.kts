plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.kevinluo.autoglm"
    compileSdk = 35
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.kevinluo.autoglm"
        minSdk = 24
        targetSdk = 34
        versionCode = 102
        versionName = "1.0.2"
        ndk { abiFilters += "arm64-v8a" }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val keystoreFile = file("release.keystore")
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("KEY_ALIAS") ?: ""
                keyPassword = System.getenv("KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        debug {
            // 不再使用 applicationIdSuffix，与发行版使用相同包名
            resValue("string", "app_name", "PhonePilot")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig =
                if (file("release.keystore").exists()) {
                    signingConfigs.getByName("release")
                } else {
                    signingConfigs.getByName("debug")
                }
        }
    }
    buildFeatures {
        buildConfig = true
        aidl = true
    }
    packaging { jniLibs { useLegacyPackaging = true; keepDebugSymbols += "**/libmihomo.so" } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }

    // Enable JUnit 5 for Kotest property-based testing
    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
        }
        unitTests.isReturnDefaultValues = true
    }
}

// Copy dev_profiles.json to assets for debug builds only
android.applicationVariants.all {
    val variant = this

    // Custom APK file name
    outputs.all {
        val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
        output.outputFileName = "AutoGLM-${variant.versionName}-${variant.buildType.name}.apk"
    }

}

dependencies {
    implementation(project(":automation-core"))
    implementation("org.yaml:snakeyaml:2.4")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.cardview)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    // Kotlin Coroutines for async operations
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // Lifecycle & ViewModel
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.runtime)

    // Security for encrypted preferences
    implementation(libs.androidx.security.crypto)

    // Navigation
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)

    // Fragment
    implementation(libs.androidx.fragment)

    // SwipeRefreshLayout
    implementation(libs.androidx.swiperefreshlayout)

    // OkHttp for API communication
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.okhttp.logging)

    // Retrofit for API communication
    implementation(libs.retrofit)

    // Kotlin Serialization for JSON parsing
    implementation(libs.kotlinx.serialization.json)

    // Sherpa-ONNX for offline speech recognition
    // Available via JitPack: https://jitpack.io/#k2-fsa/sherpa-onnx
    implementation("com.github.k2-fsa:sherpa-onnx:1.12.20")

    // Apache Commons Compress for tar.bz2 extraction
    implementation("org.apache.commons:commons-compress:1.28.0")

    // Testing
    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${libs.versions.coroutines.get()}")
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.property)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.mockk)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
