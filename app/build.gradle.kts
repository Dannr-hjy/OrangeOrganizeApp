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
        versionCode = 10
        versionName = "1.5.3"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        // 正式签名：唯一来源是项目根目录 keystore.properties（与 keystore/*.jks 一起被 gitignore，务必备份）
        // 缺文件不再退回 debug 签名 —— 见文件末尾的守卫任务
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
            // 只用正式签名，禁止退回 debug（debug 签名装的包无法覆盖升级，只能卸载重装 = 清空用户数据）
            signingConfig = signingConfigs.getByName("release")
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

// 出 release 包必须用正式签名：缺 keystore.properties 就直接失败，绝不静默退回 debug 签名。
// 只挂在 release 打包任务上，所以 :app:assembleDebug 和单测在没有密钥的环境里照样能跑。
tasks.matching { (it.name.startsWith("package") || it.name.startsWith("bundle")) && it.name.endsWith("Release") }
    .configureEach {
        doFirst {
            if (!rootProject.file("keystore.properties").exists()) {
                throw GradleException(
                    "缺少 keystore.properties，拒绝出 release 包。\n" +
                        "正式签名是 release 包的唯一合法签名：退回 debug 签名会让真机无法覆盖升级（只能卸载重装、清空数据）。\n" +
                        "请把 release keystore 与 keystore.properties 放回项目根目录（两者都在 .gitignore 里，需自行备份）。\n" +
                        "只做编译 / 跑单测请改用 :app:assembleDebug。"
                )
            }
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
