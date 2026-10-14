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
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // MP3/FLAC 等音频文件的 ID3 标签读写（歌名/歌手/专辑/歌词写进文件），移植自 mf_audit
    implementation("net.jthink:jaudiotagger:3.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
