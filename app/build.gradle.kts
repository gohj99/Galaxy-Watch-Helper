plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.gohj99.galaxywatchhelper"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.gohj99.galaxywatchhelper"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

    }

    val ciSigningConfig = providers.environmentVariable("KEYSTORE_PATH").orNull?.let { path ->
        signingConfigs.create("ciRelease") {
            storeFile = file(path)
            storePassword = providers.environmentVariable("KEY_STORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("ALIAS").get()
            keyPassword = providers.environmentVariable("KEY_PASSWORD").get()
        }
    }

    buildTypes {
        release {
            signingConfig = ciSigningConfig
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    useLibrary("wear-sdk")
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.compose.navigation)
    compileOnly(files("libs/xposed-api-82.jar"))
    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling)
    implementation(libs.core.splashscreen)
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)
    implementation(libs.wear.tooling.preview)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.ui.test.junit4)
    debugImplementation(libs.ui.test.manifest)
    debugImplementation(libs.ui.tooling)
}
