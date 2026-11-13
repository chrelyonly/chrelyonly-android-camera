plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
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
        versionCode = 20261113
        versionName = "20261113"
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
        compose = true
        viewBinding = false
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    buildToolsVersion = "37.0.0"
    ndkVersion = "28.2.13676358"
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set(
                "莓莓水印相机-${android.defaultConfig.versionName}-${variant.name}.apk"
            )
        }
    }
}
dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)

    // Compose BOM & UI
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation("androidx.compose.material:material-icons-extended")

    // CameraX
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // Location & Permissions
    implementation(libs.play.services.location)
    implementation(libs.accompanist.permissions)

    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("cn.hutool:hutool-all:5.8.48")
    implementation("com.alibaba.fastjson2:fastjson2:2.0.66")
}