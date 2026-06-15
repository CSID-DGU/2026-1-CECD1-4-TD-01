plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.galleryanalysis"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.galleryanalysis"
        minSdk = 23
        targetSdk = 35
        versionCode = 7
        versionName = "3.4.2-final-prompt-usability"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    implementation("androidx.exifinterface:exifinterface:1.4.2")

    implementation("com.google.mlkit:image-labeling:17.0.9")
    implementation("com.google.mlkit:object-detection:17.0.2")
    implementation("com.google.mlkit:face-detection:16.1.7")

    implementation("com.google.code.gson:gson:2.14.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}
