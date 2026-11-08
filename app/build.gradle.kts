// ⚠️ 这两行必须在 `plugins {}` 之前、且在文件最顶部。
// 在 build.gradle.kts 的脚本作用域里，`java` 会被解析成 JavaPluginExtension
// （就是 `java {}` 那个扩展），不是包名 —— 所以不能写 java.util.Properties，
// 必须显式 import。这个报错信息完全不提示根因，极易卡住。
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

/** 本机私有配置（不入库，见 local.properties.example）：签名口令等 */
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.kmusic.apkhd"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kmusic.apkhd"
        minSdk = 26
        targetSdk = 35
        versionCode = 86
        versionName = "2.6.4"
        multiDexEnabled = true
    }

    signingConfigs {
        // 不内置任何口令：签名信息一律从 local.properties（不入库）读取。
        // 没有配置时退回 debug 签名，保证「clone 下来就能编译」。
        val ksFile = localProps.getProperty("KMUSIC_STORE_FILE").orEmpty()
        val ksPwd = localProps.getProperty("KMUSIC_STORE_PASSWORD").orEmpty()
        val alias = localProps.getProperty("KMUSIC_KEY_ALIAS").orEmpty()
        val keyPwd = localProps.getProperty("KMUSIC_KEY_PASSWORD").orEmpty()
        val hasSigning = ksFile.isNotBlank() && ksPwd.isNotBlank() && alias.isNotBlank()
        if (hasSigning) {
            create("release") {
                storeFile = rootProject.file(ksFile)
                storePassword = ksPwd
                keyAlias = alias
                keyPassword = keyPwd.ifBlank { ksPwd }
            }
        }
    }

    lint {
        // media3 ExoPlayer 的 DefaultMediaSourceFactory 等是 @UnstableApi，
        // 全项目都要用，属既定技术选型，不做逐处 @OptIn
        disable += "UnsafeOptInUsageError"
        abortOnError = false
    }

    buildTypes {
        val signing = signingConfigs.findByName("release")
        release {
            // 开 R8：dex 从 66MB（未压缩）裁到个位数 MB。
            // 必须配合 app/proguard-rules.pro 里的 serialization 保留规则，
            // 否则 release 包会在 JSON 解析时崩、而 debug 一切正常。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 没配 local.properties 时保持未签名，避免把 Debug 签名当成正式版发出去
            signing?.let { signingConfig = it }
        }
        debug {
            signing?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests {
            // Robolectric 要读真实的 AndroidManifest（否则 SessionToken 解析不到服务，
            // 测试会以假失败告终，掩盖真正的问题）
            isIncludeAndroidResources = true
        }
    }

}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    // Compose
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    // 图标全部来自自绘的 ui/icons/MoyinIcons.kt（零 Icons.* 引用），
    // 原 material-icons-extended（约 5MB、编译期也很慢）已移除。
    implementation("androidx.compose.material3:material3")

    // Media3 播放器
    val media3 = "1.6.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    // ALAC（.m4a 苹果无损）：AOSP 没有软解器，厂商硬解会「自称支持但解不出来」
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.6.1+1")

    // 网络
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 序列化
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // 图片
    implementation("io.coil-kt:coil-compose:2.7.0")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.palette:palette-ktx:1.0.0")
    // SAF 目录遍历（用户手动指定扫描目录，MediaStore 看不见的也能扫）
    implementation("androidx.documentfile:documentfile:1.0.1")
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core-ktx:1.6.1")
}
