plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.cicidev.dynamicisland"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cicidev.dynamicisland"
        minSdk = 26          // Android 8+
        targetSdk = 29       // P40 Lite = Android 10 (EMUI 10), kısıtlamasız çalışsın
        versionCode = 2
        versionName = "2.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
