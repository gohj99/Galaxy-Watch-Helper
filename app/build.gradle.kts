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
        versionCode = 3
        versionName = "1.2"

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

// Handwritten signatures for the audited hidden NetworkAgent constructor only.
// Compile these separately: they are library inputs, never APK runtime classes.
val compileVpnApiStubs = tasks.register<JavaCompile>("compileVpnApiStubs") {
    source(fileTree("src/vpnApiStubs/java") { include("**/*.java") })
    classpath = files(androidComponents.sdkComponents.bootClasspath)
    destinationDirectory.set(layout.buildDirectory.dir("vpnApiStubs/classes"))
    sourceCompatibility = "11"
    targetCompatibility = "11"
}
val vpnApiStubsJar = tasks.register<Jar>("vpnApiStubsJar") {
    from(compileVpnApiStubs.flatMap { it.destinationDirectory })
    archiveFileName.set("vpn-api-signatures.jar")
    destinationDirectory.set(layout.buildDirectory.dir("vpnApiStubs"))
}

dependencies {
    implementation(libs.compose.navigation)
    compileOnly(files("libs/xposed-api-82.jar"))
    compileOnly(files(vpnApiStubsJar.flatMap { it.archiveFile }))
    testImplementation(libs.junit)
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
