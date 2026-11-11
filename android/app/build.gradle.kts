plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "org.openssreader.android"
    // API 37 is distributed as android-37.0; select the minor SDK explicitly.
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }
    defaultConfig {
        applicationId = "org.openssreader.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 10003
        versionName = "1.0.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // Release signing keys come exclusively from CI secrets / local environment.
    // Debug builds remain possible without private credentials.
    val releaseSigningReady = listOf(
        "OSR_SIGNING_STORE_FILE", "OSR_SIGNING_STORE_PASSWORD",
        "OSR_SIGNING_KEY_ALIAS", "OSR_SIGNING_KEY_PASSWORD"
    ).all { !System.getenv(it).isNullOrBlank() }
    signingConfigs {
        if (releaseSigningReady) {
            create("production") {
                storeFile = file(System.getenv("OSR_SIGNING_STORE_FILE"))
                storePassword = System.getenv("OSR_SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("OSR_SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("OSR_SIGNING_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseSigningReady) signingConfig = signingConfigs.getByName("production")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.04.01")
    implementation(composeBom)
    // Align compile-time stdlib with metadata of transitive AndroidX dependencies.
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.20")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.19.1")
    // Official Firefox/GeckoView engine, stable release (Oct 2026).
    implementation("org.mozilla.geckoview:geckoview:159.0.20261022150525")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // Vector/page-preserving merge of restored multi-page 1AH PDF bundles.
    // Android build and ARM64 behavior still require on-device acceptance.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    testImplementation("junit:junit:4.13.2")
    // Real Android instrumented PDF rendering/merger tests, not JVM shims.
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
