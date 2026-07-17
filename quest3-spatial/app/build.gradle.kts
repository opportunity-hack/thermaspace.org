plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.meta.spatial.plugin") version "0.13.1"
}

android {
    namespace = "org.ohack.flirone.spatial"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.ohack.flirone.spatial"
        minSdk = 34          // Horizon OS is Android 14 (API 34)
        targetSdk = 34
        versionCode = 910
        versionName = "9.1.0"
    }
    packaging { resources.excludes.add("META-INF/LICENSE") }
    lint { abortOnError = false }
    buildFeatures { buildConfig = true }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

val metaSpatialSdkVersion = "0.13.1"

dependencies {
    implementation("com.meta.spatial:meta-spatial-sdk:$metaSpatialSdkVersion")
    implementation("com.meta.spatial:meta-spatial-sdk-toolkit:$metaSpatialSdkVersion")
    implementation("com.meta.spatial:meta-spatial-sdk-vr:$metaSpatialSdkVersion")
    implementation("com.meta.spatial:meta-spatial-sdk-isdk:$metaSpatialSdkVersion")
    implementation("com.meta.spatial:meta-spatial-sdk-mruk:$metaSpatialSdkVersion")
}
