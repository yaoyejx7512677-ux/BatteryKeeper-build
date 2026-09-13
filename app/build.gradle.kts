import java.util.Properties
import java.io.FileInputStream

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties()
if (keystorePropsFile.exists()) {
    keystoreProps.load(FileInputStream(keystorePropsFile))
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.batterykeeper.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.batterykeeper.app"
        minSdk = 34
        targetSdk = 36
        versionCode = 19
        versionName = "1.6.2"

        // 小米17 / HyperOS 目标设备均为 arm64，只保留单 ABI。
        ndk {
            abiFilters += "arm64-v8a"
        }
        // 商店包只保留中英文资源，减少依赖库的多语言资源体积。
        resourceConfigurations += listOf("zh", "en")
    }

    signingConfigs {
        create("batteryKeeperFixed") {
            if (keystorePropsFile.exists()) {
                storeFile = rootProject.file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        debug {
            // CI 写入 keystore.properties 后，Debug 包也使用同一张永久证书，避免每次 Runner 随机换签名。
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("batteryKeeperFixed")
            }
        }
        release {
            // 正式商店构建：明确禁止 debuggable，并启用 R8 + 资源压缩。
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("batteryKeeperFixed")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }

    lint {
        // ML Kit so 未 strip 会触发 lintVital fatal，自用包关闭 release lint 检查
        checkReleaseBuilds = true
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // v1.5.6：改用体积更小的离线 Latin OCR。电池截图只依赖数字、日期、%、mAh 与 OS 字串，
    // 中文标签不再参与解析；仍然不依赖 Google Play Services，兼容国行 HyperOS。
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // 桌面小组件
    implementation("androidx.glance:glance-appwidget:1.1.1")
}

ksp { arg("room.generateKotlin", "true") }
