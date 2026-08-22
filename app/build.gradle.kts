import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

android {
    namespace = "com.nextpass"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nextpass"
        minSdk = 29
        targetSdk = 35
        versionCode = 3
        versionName = "1.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            // CI 自动化构建用 debug keystore 自动签名，便于直接安装；
            // 正式发布可替换为自有签名配置。
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        buildConfig = false
    }
}

dependencies {
    implementation("top.yukonga.miuix.kmp:miuix-android:0.8.8")
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.ui)
    implementation("androidx.activity:activity-compose:1.13.0")
    compileOnly("io.github.libxposed:api:102.0.0")
    compileOnly("androidx.annotation:annotation:1.8.2")
}
