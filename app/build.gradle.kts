plugins {
    alias(libs.plugins.android.application)
}

android {
    signingConfigs {
        getByName("debug") {
            storeFile = file("E:\\dev\\project\\chrelyonly-android-camera\\app\\release-key.jks")
            storePassword = "chrelyonly"
            keyAlias = "chrelyonly"
            keyPassword = "chrelyonly"
        }
        create("release") {
            storeFile = file("E:\\dev\\project\\chrelyonly-android-camera\\app\\release-key.jks")
            storePassword = "chrelyonly"
            keyAlias = "chrelyonly"
            keyPassword = "chrelyonly"
        }
    }
    namespace = "cn.chrelyonly.chrelyonly_camera"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "cn.chrelyonly.chrelyonly_camera"
        minSdk = 31
        targetSdk = 37
        versionCode = 20261106
        versionName = "20261106"
        signingConfig = signingConfigs.getByName("debug")

    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        viewBinding = false
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    buildToolsVersion = "37.0.0"
    ndkVersion = "28.2.13676358"
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)




    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("cn.hutool:hutool-all:5.8.48")
    implementation("com.alibaba.fastjson2:fastjson2:2.0.66")
}