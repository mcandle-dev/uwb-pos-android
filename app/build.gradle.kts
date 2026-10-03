plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    // D-001 — 손님 앱(dev.mcandle.uwbmember)·콘솔(com.mcandle.uwbconsole)과 한 폰에 공존 가능
    namespace = "dev.mcandle.uwbpos"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "dev.mcandle.uwbpos"
        minSdk = 30 // D-001 — 최종 POS 는 Android 11. 31+ 블루투스 권한은 SDK 분기 (constitution §3)
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-spec001"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions {
        unitTests.isReturnDefaultValues = true // android.util.Log 등을 JVM 테스트에서 no-op 으로 (EventsStoreTest)
    }
    buildFeatures {
        compose = true
        buildConfig = true // D-009 — 디버그 토글은 BuildConfig.DEBUG 에서만
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
