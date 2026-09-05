import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
}

android {
    namespace = "com.tv49.com"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tv49.com"
        minSdk = 24
        targetSdk = 36
        versionCode = 5
        versionName = "2.3.0"

        val catalogUrl = project.findProperty("tvEastCatalogUrl")?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: "https://tv49east-edge-criss-projects-7c0f74aa.vercel.app"
        buildConfigField("String", "TV_EAST_CATALOG_URL", "\"${catalogUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")

        // The publishable key is intentionally a client-side value. Supabase
        // documents publishable keys as safe to ship in mobile applications;
        // RLS/Auth remain responsible for protecting application data.
        val supabaseUrl = project.findProperty("supabaseUrl")?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: "https://aepbqfrmheihfsauzcby.supabase.co"
        val supabaseAnonKey = project.findProperty("supabaseAnonKey")?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: "sb_publishable_f331BL1gsNy-otXRmQPtrw_SG8tCWLn"
        val socialMediaUrl = project.findProperty("socialMediaUrl")?.toString()?.trim().orEmpty()
        val footballApiUrl = project.findProperty("footballApiUrl")?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: "https://site.api.espn.com/apis/site/v2/sports/soccer"
        buildConfigField("String", "SUPABASE_URL", "\"${supabaseUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${supabaseAnonKey.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("String", "SOCIAL_MEDIA_URL", "\"${socialMediaUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("String", "FOOTBALL_API_URL", "\"${footballApiUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
    }

    signingConfigs {
        create("release") {
            val props = Properties()
            rootProject.file("local.properties").takeIf { it.exists() }?.inputStream().use { stream -> stream?.let { props.load(it) } }
            val keystoreFile = props.getProperty("KEYSTORE_FILE", "")
            if (keystoreFile.isNotEmpty() && file(keystoreFile).exists()) {
                storeFile = file(keystoreFile)
                storePassword = props.getProperty("KEYSTORE_PASSWORD", "")
                keyAlias = props.getProperty("KEY_ALIAS", "")
                keyPassword = props.getProperty("KEY_PASSWORD", "")
            }
        }
    }
    val releaseSigningConfigValid = signingConfigs.getByName("release").storeFile != null
    buildTypes {
        debug { applicationIdSuffix = ".beta"; versionNameSuffix = "-beta" }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseSigningConfigValid) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests { isIncludeAndroidResources = true } }
    packaging { jniLibs.useLegacyPackaging = false }
    buildFeatures { buildConfig = true }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.activity)
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
    implementation(libs.okhttp)
    implementation(libs.gson)
    implementation(libs.viewpager2)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation("org.mockito:mockito-core:5.2.0")
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
