plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

group = "com.dylenpoki"
version = "0.1.0-SNAPSHOT"

android {
    namespace = "com.dylenpoki.dyltun"
    compileSdk = 36
    defaultConfig {
        minSdk = 21
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    api(libs.coroutines.core)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifactId = "dyltun"
                pom {
                    name.set("dyltun")
                    description.set("A Kotlin IPv4 TCP/UDP forwarding and HTTP/1.1 hooks library for Android VpnService.")
                }
            }
        }
    }
}
