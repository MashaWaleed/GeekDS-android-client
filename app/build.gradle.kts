plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.geekds"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.geekds"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.2"

    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation("com.squareup.okhttp3:okhttp:4.9.3")
    // Coroutines
    // Media3 ExoPlayer core
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    // Media3 UI components
    implementation("androidx.media3:media3-ui:1.3.1")
    // (Optional) Media3 session, if you need it
    // implementation("androidx.media3:media3-session:1.3.1")
    // Gson for JSON
    implementation("com.google.code.gson:gson:2.10.1")
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}

