plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.counseling"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.counseling"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.5"

    }

    buildFeatures {
        compose = true
    }

    sourceSets {
        getByName("main") {
            java.srcDir("src/PhenoType")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    androidResources {
        noCompress += listOf("gguf", "litertlm", "task", "tflite", "ptl")
        ignoreAssetsPattern = "*.gguf"
    }

    packaging {
        jniLibs {
            pickFirsts += listOf("lib/*/libLiteRt.so")
        }
    }
}

tasks.register("copyDatedDebugApk") {
    doLast {
        copy {
            from(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
            into(layout.buildDirectory.dir("outputs/apk/debug"))
            rename { "Counseling_06_01_v1.0.5_debug.apk" }
        }
    }
}

afterEvaluate {
    tasks.named("assembleDebug") {
        finalizedBy("copyDatedDebugApk")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.health.connect)
    implementation(libs.litertlm.android)
    implementation(libs.litert)
    implementation(libs.mediapipe.tasks.genai)
    implementation(libs.androidx.exifinterface)
    implementation(libs.gson)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.mlkit.image.labeling)
    implementation(libs.mlkit.objects)
    implementation(libs.mlkit.face.detection)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
