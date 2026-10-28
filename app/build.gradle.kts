import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.linghit.gdmusic"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.linghit.gdmusic"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"
        // 构建时间戳：崩溃日志会带上它，用来确认手机上装的是哪一版（排查"改了没生效"）
        val buildTime = SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date())
        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")

        // LiteRT-LM 自带 liblitertlm_jni.so（arm64 约 22MB），会打进所有 ABI。
        // 只保留真机用的 arm64-v8a：去掉 x86_64（模拟器）能省 26MB。
        // ⚠️ 代价：x86_64 模拟器跑不了端侧 AI（原生库缺失），其余功能正常。
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    // 统一签名：本机 ~/.android/debug.keystore 的一份副本，放项目内随 git 同步，
    // 让多台电脑编译出的 APK 签同一证书，可互相覆盖安装（不再需要卸载重装）。
    // ⚠️ 此 keystore 已进版本控制，debug 用途、无安全风险；请勿用于正式发布签名。
    signingConfigs {
        create("shared") {
            storeFile = file("${rootDir}/signing/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs["shared"]
        }
        release {
            signingConfig = signingConfigs["shared"]
            isMinifyEnabled = false
        }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Kotlin 2.4 起 jvmTarget 必须走 compilerOptions DSL，老写法 kotlinOptions.jvmTarget 已报错
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    // 「系统样式」媒体通知：NotificationCompat.MediaStyle 在 androidx.core 1.7+ 已移除，
    // 迁到了 androidx.media（androidx.media.app.NotificationCompat.MediaStyle）。
    // 荣耀/华为控制中心的「音乐卡片」靠 MediaStyle 通知 + session token 识别，
    // 普通通知它认不出来（media3-session 只把它当 runtime 依赖，编译期要用必须显式声明）。
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // MP3/FLAC 等音频文件的 ID3 标签读写（歌名/歌手/专辑/歌词写进文件），移植自 mf_audit
    implementation("net.jthink:jaudiotagger:3.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // TVBox 爬虫 dex jar 依赖 gson（jar 不自带，FongMi 宿主由 app 提供）
    implementation("com.google.code.gson:gson:2.10.1")
    // 端侧 LLM 推理（Google LiteRT-LM，替代已进维护模式的 MediaPipe LLM Inference）。
    // 在手机本地跑 .litertlm 模型（Qwen3-0.6B / 1.7B / 4B），零 Key、零费用、不联网。
    // ⚠️ 该 AAR 带 Kotlin 2.4 metadata；若以后引入 Hilt/Dagger 2.59 会报
    //    "Provided Metadata instance has version 2.4.0, maximum supported 2.3.0"，
    //    届时把 Dagger 升到 2.60.1+ 或在 ksp 加 kotlin-metadata-jvm:2.4.21 即可。
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.1")
    // 端侧离线语音识别（Vosk，纯本地、不联网）：中文小模型约 42MB
    implementation("com.alphacephei:vosk-android:0.3.47")
}
