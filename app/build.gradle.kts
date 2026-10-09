import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.dannr.chengzikb"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dannr.chengzikb"
        // minSdk 26 = Android 8.0：可直接使用 java.time，无需 core-library-desugaring
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "1.5.1"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        // 正式签名：从项目根目录 keystore.properties 读取；文件缺失时退回 debug 签名（便于无密钥环境/他人克隆）
        create("release") {
            val props = Properties()
            val propFile = rootProject.file("keystore.properties")
            if (propFile.exists()) {
                propFile.inputStream().use { props.load(it) }
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // 仅用于本机编译/跑单测；真机一律装 release（同签名可覆盖升级、保留数据），不再上 debug
        }
        release {
            // 正式发布 / 真机装机均走本 type：正式签名 + 版本号递增；R8 暂未开启
            isMinifyEnabled = false
            signingConfig = if (rootProject.file("keystore.properties").exists())
                signingConfigs.getByName("release")
            else
                signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        encoding = "UTF-8"
    }

    kotlinOptions {
        jvmTarget = JvmTarget.JVM_17.target
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    // JVM 单测需要 org.json 实现（Android 平台自带，测试运行在 JVM）
    testImplementation(libs.orgjson)
    testImplementation(libs.robolectric)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
