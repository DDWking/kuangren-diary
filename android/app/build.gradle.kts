import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val localProps = Properties().apply {
    val candidates = listOf(
        rootProject.projectDir.resolve("local.properties"),
        rootProject.projectDir.resolve("../local.properties")
    )
    candidates.firstOrNull { it.exists() }?.inputStream()?.use { load(it) }
}

fun cfg(key: String, fallback: String): String {
    val raw = localProps.getProperty(key)?.trim()
    val v = if (raw.isNullOrEmpty()) fallback else raw
    return v.replace("\\", "\\\\").replace("\"", "\\\"")
}

val keystoreProps = Properties().apply {
    val f = rootProject.projectDir.resolve("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseSigning = keystoreProps.getProperty("storeFile") != null &&
    rootProject.projectDir.resolve(keystoreProps.getProperty("storeFile")!!).exists()

android {
    namespace = "com.ddw.kuangren"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ddw.kuangren"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "API_BASE", "\"" + cfg("kuangren.apiBase", "http://159.75.236.90/kuangren-api") + "\"")
        buildConfigField("String", "API_TOKEN", "\"" + cfg("kuangren.token", "") + "\"")
        buildConfigField("String", "DEVICE_ID", "\"phone-main\"")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.projectDir.resolve(keystoreProps.getProperty("storeFile")!!)
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeUi = "1.8.2"
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.compose.ui:ui:" + composeUi)
    implementation("androidx.compose.ui:ui-graphics:" + composeUi)
    implementation("androidx.compose.foundation:foundation:" + composeUi)
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
}
