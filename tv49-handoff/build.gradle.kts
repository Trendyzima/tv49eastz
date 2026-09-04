plugins {
    id("com.android.library") version "8.13.1"
}

android {
    namespace = "com.tv49east.handoff"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
