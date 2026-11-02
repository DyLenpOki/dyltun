plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.dylenpoki.dyltun.sample"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.dylenpoki.dyltun.sample"
        minSdk = 21
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":dyltun"))
}
