plugins {
    id("com.android.library")
}

android {
    namespace = "com.tv49east.observability"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.posthog.android)
    implementation(libs.sentry.android)
    implementation(libs.cloudinary.android)
}
